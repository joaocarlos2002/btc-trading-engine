package dev.romeo.btctradingengine.indicator;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Highest (or lowest) value of the last {@code period} values in amortized O(1), via a monotonic
 * deque (issue #95). Scanning the whole window every candle was the dominant backtest cost.
 *
 * Ties resolve to the earliest value in the window, which is what a left-to-right
 * {@code BigDecimal.max}/{@code min} scan returns - so the result keeps the same scale too, not just
 * the same numeric value.
 */
final class RollingExtremum {
    private final int period;
    private final boolean highest;
    /** Candidates oldest first; values strictly decrease (max) or increase (min) from front to back. */
    private final Deque<Entry> candidates = new ArrayDeque<>();
    private long count;

    private RollingExtremum(int period, boolean highest) {
        this.period = period;
        this.highest = highest;
    }

    static RollingExtremum max(int period) {
        return new RollingExtremum(period, true);
    }

    static RollingExtremum min(int period) {
        return new RollingExtremum(period, false);
    }

    void add(BigDecimal value) {
        // A newer value that beats an older one outlives it in the window, so the older one can never
        // be the answer again. Equal values stay: the earlier one wins ties.
        while (!candidates.isEmpty() && beaten(candidates.peekLast().value(), value)) {
            candidates.removeLast();
        }
        candidates.addLast(new Entry(count, value));
        count++;

        long oldestInWindow = count - period;
        while (!candidates.isEmpty() && candidates.peekFirst().index() < oldestInWindow) {
            candidates.removeFirst();
        }
    }

    /** How many values the window currently holds (at most period). */
    int size() {
        return (int) Math.min(count, Math.max(period, 0));
    }

    /** The extremum of the window, or null while it is empty. */
    BigDecimal value() {
        Entry first = candidates.peekFirst();
        return first == null ? null : first.value();
    }

    private boolean beaten(BigDecimal older, BigDecimal newer) {
        int cmp = older.compareTo(newer);
        return highest ? cmp < 0 : cmp > 0;
    }

    private record Entry(long index, BigDecimal value) {}
}
