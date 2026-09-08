package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

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

    public CandleAggregator(Duration interval, CandleEventListener listener) {
        this.interval = Objects.requireNonNull(interval, "intervalo");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Intervalo deve ser positivo");
        }
        intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("Intervalo deve ter pelo menos 1 ms");
        }
        this.listener = Objects.requireNonNull(listener, "Escutando...");
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
            Instant closeTime = bucketStart.plus(interval);
            listener.onEvent(new CandleEvent(
                    instrument,
                    bucketStart,
                    closeTime,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    tickCount));
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
    }

    private void updateCandle(NormalizedPriceEvent event) {
        BigDecimal price = event.price();
        high = high.max(price);
        low = low.min(price);
        close = price;
        volume = volume.add(event.quantity());
        tickCount++;
    }

    private synchronized void closeExpiredCandle() {
        if (bucketStart == null) {
            return;
        }
        Instant now = Instant.now();
        Instant nextBucketStart = bucketStart.plus(interval);
        if (now.isAfter(nextBucketStart) || now.equals(nextBucketStart)) {
            listener.onEvent(new CandleEvent(
                    instrument,
                    bucketStart,
                    nextBucketStart,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    tickCount));
            bucketStart = null;
        }
    }
}

