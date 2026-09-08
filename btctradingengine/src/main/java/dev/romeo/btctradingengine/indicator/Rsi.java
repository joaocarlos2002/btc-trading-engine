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
    private Deque<BigDecimal> dts = new ArrayDeque<>();

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
            if (dts.size() > n) dts.removeFirst();
            if (dts.size() < n) return Optional.empty();
        }

        BigDecimal sumGain = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;


        int count = 0;
        for (BigDecimal val : dts) {

            if (val.compareTo(BigDecimal.ZERO) >= 0) {
                sumGain = sumGain.add(val);
            } else {
                sumLoss = sumLoss.add(val.abs());
            }

            count++;
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
}

