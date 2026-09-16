package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * Rolling window of the last candles, sized to the largest window a feature reads (issue #71).
 * It used to be a fixed 100 candles while volatility and volume average asked for 300, so both
 * were always "not enough history" and VolatilityRule and absorption never fired.
 */
public class FeatureBuffer {

    private final int capacity;
    private final Deque<CandleEvent> buffer;
    /** Close-to-close returns aligned with the candles: returns.get(i) is the return INTO candle i+1. */
    private final Deque<Double> returns;

    public FeatureBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity + 1);
        this.returns = new ArrayDeque<>(capacity);
    }

    public void add(CandleEvent candle) {
        CandleEvent previous = buffer.peekLast();
        if (previous != null) {
            returns.addLast(simpleReturn(previous.close(), candle.close()));
        }
        buffer.addLast(candle);
        if (buffer.size() > capacity) {
            buffer.removeFirst();
            returns.removeFirst();
        }
    }

    /**
     * Population standard deviation of the last periods-1 close-to-close returns, in %. Computed in
     * double over the cached returns (issue #95): the old version rebuilt two lists and ran BigDecimal
     * pow/sqrt per candle, and the extra precision is noise next to a 1-minute return.
     */
    public Optional<BigDecimal> volatility(int periods) {
        if (periods > capacity) {
            throw new IllegalArgumentException("volatility window " + periods + " exceeds buffer capacity " + capacity);
        }
        if (buffer.size() < periods || periods < 2) {
            return Optional.empty();
        }

        int count = periods - 1;
        double sum = 0;
        double sumSquares = 0;
        Iterator<Double> it = returns.descendingIterator();
        for (int i = 0; i < count; i++) {
            double r = it.next();
            sum += r;
            sumSquares += r * r;
        }
        double mean = sum / count;
        double variance = Math.max(0, sumSquares / count - mean * mean);
        return Optional.of(BigDecimal.valueOf(Math.sqrt(variance) * 100).setScale(10, RoundingMode.HALF_UP));
    }

    public Optional<BigDecimal> averageVolume(int periods) {
        if (periods > capacity) {
            throw new IllegalArgumentException("volume window " + periods + " exceeds buffer capacity " + capacity);
        }
        if (buffer.size() < periods) {
            return Optional.empty();
        }

        BigDecimal sum = BigDecimal.ZERO;
        Iterator<CandleEvent> it = buffer.descendingIterator();
        for (int i = 0; i < periods; i++) {
            sum = sum.add(it.next().volume());
        }
        return Optional.of(sum.divide(BigDecimal.valueOf(periods), 8, RoundingMode.HALF_UP));
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return buffer.size();
    }

    public Optional<CandleEvent> getLast() {
        return Optional.ofNullable(buffer.peekLast());
    }

    private static double simpleReturn(BigDecimal previousClose, BigDecimal close) {
        double prev = previousClose.doubleValue();
        return prev > 0 ? (close.doubleValue() - prev) / prev : 0;
    }
}
