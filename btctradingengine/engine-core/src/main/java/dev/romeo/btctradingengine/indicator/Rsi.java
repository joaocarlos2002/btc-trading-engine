package dev.romeo.btctradingengine.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class Rsi {
    private final int n;
    private BigDecimal previousPrice;
    private boolean seeded = false;
    private final Deque<BigDecimal> dts = new ArrayDeque<>();
    /** Running sums over dts (issue #95): exact BigDecimal add/subtract, so no drift and no rescan. */
    private BigDecimal sumGain = BigDecimal.ZERO;
    private BigDecimal sumLoss = BigDecimal.ZERO;

    public Rsi(int n) {
        this.n = n;
    }

    public synchronized Optional<BigDecimal> update(BigDecimal p) {
        if (!seeded) {
            seeded = true;
            previousPrice = p;
            return Optional.empty();
        } else {
            BigDecimal delta = p.subtract(previousPrice);
            previousPrice = p;

            dts.addLast(delta);
            accumulate(delta, true);
            if (dts.size() > n) accumulate(dts.removeFirst(), false);
            if (dts.size() < n) return Optional.empty();
        }

        BigDecimal gain = sumGain.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);
        BigDecimal loss = sumLoss.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);

        if (loss.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.of(BigDecimal.valueOf(100));
        }

        BigDecimal rs = gain.divide(loss, 8, RoundingMode.HALF_EVEN);
        BigDecimal rsi = BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                .divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_EVEN));

        return Optional.of(rsi);
    }

    private void accumulate(BigDecimal delta, boolean entering) {
        if (delta.compareTo(BigDecimal.ZERO) >= 0) {
            sumGain = entering ? sumGain.add(delta) : sumGain.subtract(delta);
        } else {
            BigDecimal loss = delta.abs();
            sumLoss = entering ? sumLoss.add(loss) : sumLoss.subtract(loss);
        }
    }
}

