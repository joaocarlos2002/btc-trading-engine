package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SmaTest {

    //helper
    private NormalizedPriceEvent event(String price) {
        return new NormalizedPriceEvent("BTC/USD", new BigDecimal(price), Instant.now(), Instant.now());
    }

    @Test
    public void testSma() {
        int n = 3;

        List<NormalizedPriceEvent> events = List.of(
                event("10"), event("12"), event("11"), event("14"), event("13")
        );

        Sma sma = new Sma(n);

        Optional<BigDecimal> resultReceipt = sma.calculate(events);
        BigDecimal resultExpected = new BigDecimal("12.66666667");


        assertEquals(resultExpected, resultReceipt.get());
    }
}


