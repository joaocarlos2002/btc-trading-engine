package dev.romeo.btctradingengine.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SmaIncrementalTest {

    @Test
    public void testSmaIncremental() {
        BigDecimal resultExpected = new BigDecimal("12.66666667");
        int n = 3;

        SmaIncremental smaIncremental = new SmaIncremental(n);
        smaIncremental.updateSum(new BigDecimal("10"));
        smaIncremental.updateSum(new BigDecimal("12"));
        smaIncremental.updateSum(new BigDecimal("11"));
        smaIncremental.updateSum(new BigDecimal("14"));
        Optional<BigDecimal> result = smaIncremental.updateSum(new BigDecimal("13"));

        assertEquals(resultExpected, result.get());
    }
}


