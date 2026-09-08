package dev.romeo.btctradingengine.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EmaTest {

    @Test
    public void emaTest() {
        BigDecimal resultExpected = new BigDecimal("12.50000000");
        int n = 3;

        Ema ema = new Ema(n);
        ema.update(BigDecimal.valueOf(10));
        ema.update(BigDecimal.valueOf(12));
        ema.update(BigDecimal.valueOf(11));
        BigDecimal results = ema.update(BigDecimal.valueOf(14));

        assertEquals(resultExpected, results);
    }
    @Test
    public void emaFormulaTest() {
        BigDecimal resultExpected = new BigDecimal("11.00000000");
        int n = 3;

        Ema ema = new Ema(n);
        ema.update(BigDecimal.valueOf(10));
        ema.update(BigDecimal.valueOf(12));
        BigDecimal results = ema.update(BigDecimal.valueOf(11));

        assertEquals(resultExpected, results);
    }

}


