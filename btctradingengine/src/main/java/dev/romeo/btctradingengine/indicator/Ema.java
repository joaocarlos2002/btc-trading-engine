package dev.romeo.btctradingengine.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Ema {
    private final BigDecimal alpha;
    private boolean seeded = false;
    private BigDecimal previousEma;

    public Ema(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("O perÃ­odo (n) deve ser maior que zero.");
        }
        this.alpha = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(n + 1), 8, RoundingMode.HALF_UP);
    }

    public synchronized BigDecimal update(BigDecimal np) {
        if (!seeded) {
            seeded = true;
            return previousEma = np.setScale(8, RoundingMode.HALF_UP);
        }
        previousEma = alpha
                .multiply(np)
                .add(BigDecimal.ONE.subtract(alpha).multiply(previousEma))
                .setScale(8, RoundingMode.HALF_EVEN);
        return previousEma;
    }
}

