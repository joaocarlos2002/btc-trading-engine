package dev.romeo.btctradingengine.derivatives;

import dev.romeo.btctradingengine.feature.DerivFeatures;
import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Timestamped derivatives readings and the single rule that turns them into a candle's DerivFeatures.
 *
 * The live poller and the backtest fill this same class, so "which reading belongs to this candle"
 * has one definition: the latest reading at or before the candle's close, and only while it is not
 * stale. A reading timestamped after the close is never visible, which is what keeps the backtest
 * free of lookahead.
 *
 * Basis is the exception: it pairs the spot candle with the perpetual kline of the same open time,
 * so it only works when market.interval.seconds matches market.binance.interval, and it is null
 * when that kline is missing.
 */
public class DerivativesHistory implements DerivativesLookup {
    /** BTCUSDT settles funding every 8h, so a settled rate stays the current one for that long. */
    public static final Duration FUNDING_INTERVAL = Duration.ofHours(8);

    private static final int SCALE = 8;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ConcurrentSkipListMap<Long, BigDecimal> fundingRates = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<Long, BigDecimal> openInterest = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<Long, BigDecimal> longShortRatios = new ConcurrentSkipListMap<>();
    /** Keyed by kline open time. */
    private final ConcurrentSkipListMap<Long, BigDecimal> perpCloses = new ConcurrentSkipListMap<>();

    private final long staleMillis;
    private final long openInterestChangeMillis;
    /** 0 keeps everything (backtests); live trims so a long-running bot does not grow without bound. */
    private final long retentionMillis;

    public DerivativesHistory(Duration staleAfter, Duration openInterestChangeWindow, Duration retention) {
        this.staleMillis = staleAfter.toMillis();
        this.openInterestChangeMillis = openInterestChangeWindow.toMillis();
        this.retentionMillis = retention.toMillis();
    }

    /** Retention covers the warmup candles plus the longest look-back (funding interval, OI window). */
    public static DerivativesHistory forLive(Duration staleAfter, Duration changeWindow, Duration candleInterval,
                                             int historyCandles) {
        Duration retention = candleInterval.multipliedBy(historyCandles)
                .plus(FUNDING_INTERVAL)
                .plus(changeWindow)
                .plus(staleAfter);
        return new DerivativesHistory(staleAfter, changeWindow, retention);
    }

    public static DerivativesHistory forBacktest(Duration staleAfter, Duration changeWindow) {
        return new DerivativesHistory(staleAfter, changeWindow, Duration.ZERO);
    }

    /** Keyed by the settlement time: the rate is only known once it has been settled. */
    public void addFundingRate(Instant fundingTime, BigDecimal rate) {
        put(fundingRates, fundingTime, rate);
    }

    public void addOpenInterest(Instant observedAt, BigDecimal value) {
        put(openInterest, observedAt, value);
    }

    public void addLongShortRatio(Instant observedAt, BigDecimal ratio) {
        put(longShortRatios, observedAt, ratio);
    }

    /** A later update of the still-open kline replaces the earlier close for the same open time. */
    public void addPerpClose(Instant openTime, BigDecimal close) {
        put(perpCloses, openTime, close);
    }

    public boolean isEmpty() {
        return fundingRates.isEmpty() && openInterest.isEmpty()
                && longShortRatios.isEmpty() && perpCloses.isEmpty();
    }

    @Override
    public DerivFeatures featuresFor(CandleEvent candle) {
        long closeTime = candle.closeTime().toEpochMilli();
        BigDecimal openInterestNow = valueAt(openInterest, closeTime, staleMillis);
        BigDecimal openInterestBefore = valueAt(openInterest, closeTime - openInterestChangeMillis, staleMillis);

        return new DerivFeatures(
                openInterestNow,
                valueAt(fundingRates, closeTime, FUNDING_INTERVAL.toMillis() + staleMillis),
                basisPercent(candle),
                percentChange(openInterestNow, openInterestBefore),
                valueAt(longShortRatios, closeTime, staleMillis));
    }

    private BigDecimal basisPercent(CandleEvent candle) {
        BigDecimal perpClose = perpCloses.get(candle.openTime().toEpochMilli());
        if (perpClose == null || candle.close().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return perpClose.subtract(candle.close())
                .divide(candle.close(), SCALE, RoundingMode.HALF_EVEN)
                .multiply(HUNDRED);
    }

    private static BigDecimal percentChange(BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return now.subtract(before)
                .divide(before, SCALE, RoundingMode.HALF_EVEN)
                .multiply(HUNDRED);
    }

    /** Latest reading at or before the given time, or null if there is none or it is older than maxAge. */
    private static BigDecimal valueAt(ConcurrentSkipListMap<Long, BigDecimal> series, long time, long maxAgeMillis) {
        Map.Entry<Long, BigDecimal> entry = series.floorEntry(time);
        if (entry == null || time - entry.getKey() > maxAgeMillis) {
            return null;
        }
        return entry.getValue();
    }

    private void put(ConcurrentSkipListMap<Long, BigDecimal> series, Instant time, BigDecimal value) {
        series.put(time.toEpochMilli(), value);
        if (retentionMillis > 0) {
            series.headMap(series.lastKey() - retentionMillis).clear();
        }
    }
}
