package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.model.TradeFlow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CandleAggregator implements PriceEventListener {

    private final Duration interval;
    private final long intervalMillis;
    private final CandleEventListener listener;
    private final BigDecimal largeTradeNotional;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "CandleAggregator-Timer");
        t.setDaemon(true);
        return t;
    });

    private String instrument;
    private Instant bucketStart;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
    private int tickCount;

    private BigDecimal buyVolume;
    private BigDecimal sellVolume;
    private BigDecimal largeBuyVolume;
    private BigDecimal largeSellVolume;
    /** A single trade with an unknown side makes the candle's split unreliable, so it reports no flow. */
    private boolean sideUnknown;

    public CandleAggregator(Duration interval, CandleEventListener listener) {
        this(interval, listener, Config.getLargeTradeNotional());
    }

    public CandleAggregator(Duration interval, CandleEventListener listener, BigDecimal largeTradeNotional) {
        this.interval = Objects.requireNonNull(interval, "intervalo");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Intervalo deve ser positivo");
        }
        intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("Intervalo deve ter pelo menos 1 ms");
        }
        this.listener = Objects.requireNonNull(listener, "Escutando...");
        this.largeTradeNotional = Objects.requireNonNull(largeTradeNotional, "largeTradeNotional");
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::closeExpiredCandle, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdown();
    }

    @Override
    public synchronized void onEvent(NormalizedPriceEvent event) {
        Instant bucket = bucketStart(event.eventTimestamp());

        if (bucketStart == null) {
            openCandle(event, bucket);
        } else if (bucket.equals(bucketStart)) {
            updateCandle(event);
        } else {
            listener.onEvent(buildCandle(bucketStart.plus(interval)));
            openCandle(event, bucket);
        }
    }

    private Instant bucketStart(Instant timestamp) {
        long epochMillis = timestamp.toEpochMilli();
        long bucketMillis = Math.multiplyExact(Math.floorDiv(epochMillis, intervalMillis), intervalMillis);
        return Instant.ofEpochMilli(bucketMillis);
    }

    private void openCandle(NormalizedPriceEvent event, Instant bucket) {
        instrument = event.instrument();
        bucketStart = bucket;
        open = event.price();
        high = event.price();
        low = event.price();
        close = event.price();
        volume = event.quantity();
        tickCount = 1;

        buyVolume = BigDecimal.ZERO;
        sellVolume = BigDecimal.ZERO;
        largeBuyVolume = BigDecimal.ZERO;
        largeSellVolume = BigDecimal.ZERO;
        sideUnknown = false;
        addFlow(event);
    }

    private void updateCandle(NormalizedPriceEvent event) {
        BigDecimal price = event.price();
        high = high.max(price);
        low = low.min(price);
        close = price;
        volume = volume.add(event.quantity());
        tickCount++;
        addFlow(event);
    }

    /**
     * Splits the trade's quantity by aggressor side and by size. Size is the notional of one
     * aggTrade, which groups the fills of a taker order at a single price - a market order that
     * sweeps several levels becomes several smaller aggTrades, so large trades are undercounted.
     */
    private void addFlow(NormalizedPriceEvent event) {
        if (event.aggressorSide() == AggressorSide.UNKNOWN) {
            sideUnknown = true;
            return;
        }
        BigDecimal quantity = event.quantity();
        boolean large = event.price().multiply(quantity).compareTo(largeTradeNotional) >= 0;
        if (event.aggressorSide() == AggressorSide.BUY) {
            buyVolume = buyVolume.add(quantity);
            if (large) {
                largeBuyVolume = largeBuyVolume.add(quantity);
            }
        } else {
            sellVolume = sellVolume.add(quantity);
            if (large) {
                largeSellVolume = largeSellVolume.add(quantity);
            }
        }
    }

    private CandleEvent buildCandle(Instant closeTime) {
        TradeFlow flow = sideUnknown
                ? TradeFlow.none()
                : TradeFlow.fromTrades(buyVolume, sellVolume, largeBuyVolume, largeSellVolume);
        return new CandleEvent(instrument, bucketStart, closeTime, open, high, low, close, volume, tickCount, flow);
    }

    private synchronized void closeExpiredCandle() {
        if (bucketStart == null) {
            return;
        }
        Instant now = Instant.now();
        Instant nextBucketStart = bucketStart.plus(interval);
        if (now.isAfter(nextBucketStart) || now.equals(nextBucketStart)) {
            listener.onEvent(buildCandle(nextBucketStart));
            bucketStart = null;
        }
    }
}
