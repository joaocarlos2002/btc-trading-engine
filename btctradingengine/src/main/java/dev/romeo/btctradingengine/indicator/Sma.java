package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public class Sma {
    private final int n;
    private final BigDecimal runningSum =  BigDecimal.ZERO;

    public Sma(int n) {
        this.n = n;
    }

    public Optional<BigDecimal> calculate(List<NormalizedPriceEvent> events) {
        if (events.size() < n) {
            return Optional.empty();
        } else {
            BigDecimal sum = BigDecimal.ZERO;

            // comeÃ§a em size - p
            // e vai atÃ© o fim da lista
            // com size=5 e p=3, i comeÃ§a em 2 e roda 2,3,4 â†’ soma 11+14+13 = 38 â†’ /3 = 12,67
            for (int i = events.size() - n; i < events.size() ; i++) {
                sum = sum.add(events.get(i).price());
            }

            BigDecimal media = sum.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);

            return Optional.of(media);
        }
    }
}

