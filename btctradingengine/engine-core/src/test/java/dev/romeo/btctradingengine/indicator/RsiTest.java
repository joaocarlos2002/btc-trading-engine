package dev.romeo.btctradingengine.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RsiTest {
    @Test
    public void RsiTest() {
        BigDecimal resultExpected = new BigDecimal("35.29");
        int n = 3;

        Rsi rsi = new Rsi(n);
        rsi.update(new BigDecimal("10"));
        rsi.update(new BigDecimal("11"));
        rsi.update(new BigDecimal("10"));
        rsi.update(new BigDecimal("5"));
        rsi.update(new BigDecimal("11"));
        Optional<BigDecimal> result = rsi.update(new BigDecimal("5"));

        assertEquals(resultExpected, result.get().setScale(2, RoundingMode.HALF_EVEN));
    }
}


