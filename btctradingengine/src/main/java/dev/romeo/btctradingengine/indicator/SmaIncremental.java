package dev.romeo.btctradingengine.indicator;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class SmaIncremental {
    private BigDecimal runningSum = BigDecimal.ZERO;
    private final Deque<BigDecimal> queue = new ArrayDeque<>();
    private final int p;

    public SmaIncremental(int p) {
        this.p = p;
    }

    public synchronized Optional<BigDecimal> updateSum(BigDecimal np) {
        runningSum = runningSum.add(np);
        queue.addLast(np);

        if (queue.size() > p) {
            runningSum = runningSum.subtract(queue.removeFirst());
        }

        if (queue.size() <  p) {
            return Optional.empty();
        }
        return Optional.of(runningSum.divide(BigDecimal.valueOf(p), 8, RoundingMode.HALF_EVEN));
    }
}

