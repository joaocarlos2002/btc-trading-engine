package dev.romeo.btctradingengine.derivatives;

import dev.romeo.btctradingengine.adapter.BinanceKlineClient;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.persistence.DerivativesSnapshotWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live feed of {@link DerivativesHistory}: polls the futures REST API in the background. Every call
 * fails on its own - a futures outage only leaves the matching DerivFeatures fields null, it never
 * stops the bot.
 *
 * Two schedules on separate threads, so a slow reading call cannot delay the basis: the perpetual
 * close every derivatives.basis.poll.seconds, and funding / open interest / long-short every
 * derivatives.poll.seconds.
 */
public class DerivativesPoller {
    private static final Logger logger = LoggerFactory.getLogger(DerivativesPoller.class);
    private static final int LATEST_KLINES = 2;

    private final BinanceFuturesClient client;
    private final DerivativesHistory history;
    private final DerivativesSnapshotWriter writer;
    private final String symbol;
    private final String interval;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "DerivativesPoller");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean basisFailing;

    public DerivativesPoller(BinanceFuturesClient client, DerivativesHistory history,
                             DerivativesSnapshotWriter writer, String symbol, String interval) {
        this.client = client;
        this.history = history;
        this.writer = writer;
        this.symbol = symbol;
        this.interval = interval;
    }

    /**
     * Loads past perpetual klines and funding so the indicator warmup candles get basis and funding
     * too. Must run before the warmup; a failure only leaves those candles without derivatives.
     */
    public void seed(int historyCandles) {
        try {
            BinanceKlineClient.usdmFutures().loadClosedCandles(symbol, interval, historyCandles)
                    .forEach(kline -> history.addPerpClose(kline.openTime(), kline.close()));
        } catch (Exception e) {
            logger.warn("Could not load perpetual klines for the warmup basis: {}", e.getMessage());
        }
        try {
            Instant now = Instant.now();
            // One extra funding interval so the oldest warmup candle already has a settled rate
            Duration span = Config.getMarketInterval().multipliedBy(historyCandles)
                    .plus(DerivativesHistory.FUNDING_INTERVAL);
            client.fundingRates(symbol, now.minus(span), now)
                    .forEach(rate -> history.addFundingRate(rate.time(), rate.value()));
        } catch (Exception e) {
            logger.warn("Could not load funding history for the warmup: {}", e.getMessage());
        }
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::pollBasis, 0, Config.getDerivativesBasisPollSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::pollReadings, 0, Config.getDerivativesPollSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * The still-open kline's close keeps being overwritten, so at a spot candle's close the basis uses
     * a perpetual price at most derivatives.basis.poll.seconds old - the backtest uses the final kline
     * close instead. Logged only when the state changes, since this runs every few seconds.
     */
    private void pollBasis() {
        try {
            client.latestKlineCloses(symbol, interval, LATEST_KLINES)
                    .forEach(kline -> history.addPerpClose(kline.time(), kline.value()));
            if (basisFailing) {
                logger.info("Perpetual kline polling recovered");
                basisFailing = false;
            }
        } catch (Exception e) {
            if (!basisFailing) {
                logger.warn("Perpetual kline polling failed, basis unavailable until it recovers: {}", e.getMessage());
                basisFailing = true;
            }
        }
    }

    /**
     * Open interest and long/short are stored under the time the bot received them, not Binance's
     * period timestamp: that guarantees a reading is never attached to a candle that closed before
     * the value was actually known, including when the persisted snapshots are replayed later.
     */
    private void pollReadings() {
        Instant observedAt = Instant.now();
        BigDecimal fundingRate = null;
        BigDecimal openInterest = null;
        BigDecimal longShortRatio = null;

        try {
            var latest = client.latestFundingRate(symbol);
            if (latest.isPresent()) {
                history.addFundingRate(latest.get().time(), latest.get().value());
                fundingRate = latest.get().value();
            }
        } catch (Exception e) {
            logger.warn("Funding rate poll failed: {}", e.getMessage());
        }
        try {
            openInterest = client.openInterest(symbol).value();
            history.addOpenInterest(observedAt, openInterest);
        } catch (Exception e) {
            logger.warn("Open interest poll failed: {}", e.getMessage());
        }
        try {
            var latest = client.longShortRatio(symbol);
            if (latest.isPresent()) {
                longShortRatio = latest.get().value();
                history.addLongShortRatio(observedAt, longShortRatio);
            }
        } catch (Exception e) {
            logger.warn("Long/short ratio poll failed: {}", e.getMessage());
        }

        if (fundingRate != null || openInterest != null || longShortRatio != null) {
            writer.write(symbol, observedAt, openInterest, longShortRatio, fundingRate);
        }
    }
}
