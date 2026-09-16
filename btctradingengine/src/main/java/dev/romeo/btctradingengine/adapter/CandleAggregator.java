package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.model.TradeFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class CandleAggregator implements PriceEventListener {
    private static final Logger logger = LoggerFactory.getLogger(CandleAggregator.class);

    /** The timer fires this long after each interval boundary so trades stamped just before it still arrive (issue #74). */
    static final Duration CLOSE_TOLERANCE = Duration.ofMillis(250);

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

    /**
     * Open time of the last candle handed to the listener. A late tick from that window (or an older
     * one) would reopen a candle that was already emitted and emit it a second time with the same
     * openTime, so it is dropped instead (issue #74).
     */
    private Instant lastEmittedBucket;
    private final AtomicLong lateTicksDropped = new AtomicLong();

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
        // Aligned to the next interval boundary plus a tolerance, not to whenever start() ran (issue #74).
        scheduler.scheduleAtFixedRate(this::closeExpiredCandle, initialTimerDelayMillis(Instant.now()),
                intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Milliseconds from now until the next interval boundary plus CLOSE_TOLERANCE. */
    long initialTimerDelayMillis(Instant now) {
        Instant nextBoundary = bucketStart(now).plus(interval);
        return Duration.between(now, nextBoundary).plus(CLOSE_TOLERANCE).toMillis();
    }

    /** Ticks dropped because their window was already emitted (issue #74). */
    public long getLateTicksDropped() {
        return lateTicksDropped.get();
    }

    public void stop() {
        scheduler.shutdown();
    }

    @Override
    public synchronized void onEvent(NormalizedPriceEvent event) {
        Instant bucket = bucketStart(event.eventTimestamp());

        if (isLate(bucket)) {
            dropLateTick(event, bucket);
            return;
        }

        if (bucketStart == null) {
            openCandle(event, bucket);
        } else if (bucket.equals(bucketStart)) {
            updateCandle(event);
        } else {
            emitCurrentCandle();
            openCandle(event, bucket);
        }
    }

    /** A tick is late when its window was already emitted or is older than the candle being built. */
    private boolean isLate(Instant bucket) {
        return (lastEmittedBucket != null && !bucket.isAfter(lastEmittedBucket))
                || (bucketStart != null && bucket.isBefore(bucketStart));
    }

    private void dropLateTick(NormalizedPriceEvent event, Instant bucket) {
        long dropped = lateTicksDropped.incrementAndGet();
        if (dropped == 1 || dropped % 1000 == 0) {
            logger.warn("Dropped late tick for already emitted window {} (event time {}); {} late ticks dropped so far",
                    bucket, event.eventTimestamp(), dropped);
        } else {
            logger.debug("Dropped late tick for already emitted window {} (event time {})",
                    bucket, event.eventTimestamp());
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

    private void emitCurrentCandle() {
        listener.onEvent(buildCandle(bucketStart.plus(interval)));
        lastEmittedBucket = bucketStart;
    }

    private void closeExpiredCandle() {
        closeExpiredCandle(Instant.now());
    }

    synchronized void closeExpiredCandle(Instant now) {
        if (bucketStart == null) {
            return;
        }
        Instant nextBucketStart = bucketStart.plus(interval);
        if (!now.isBefore(nextBucketStart)) {
            emitCurrentCandle();
            bucketStart = null;
        }
    }
}
