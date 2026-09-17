package dev.romeo.btctradingengine.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BollingerBandsTest {

    @Test
    public void returnsEmptyDuringWarmup() {
        BollingerBands bands = new BollingerBands(4, new BigDecimal("2.0"));

        assertTrue(bands.update(new BigDecimal("10")).isEmpty());
        assertTrue(bands.update(new BigDecimal("12")).isEmpty());
        assertTrue(bands.update(new BigDecimal("14")).isEmpty());
        assertTrue(bands.update(new BigDecimal("16")).isPresent());
    }

    @Test
    public void calculatesKnownBands() {
        BollingerBands bands = new BollingerBands(4, new BigDecimal("2.0"));

        bands.update(new BigDecimal("10"));
        bands.update(new BigDecimal("12"));
        bands.update(new BigDecimal("14"));
        Optional<BollingerBands.BollingerValue> result = bands.update(new BigDecimal("16"));

        // middle = 13, population stddev = sqrt(20/4) = sqrt(5) = 2.23606798
        BollingerBands.BollingerValue value = result.orElseThrow();
        assertEquals(new BigDecimal("13.0000"), value.middle().setScale(4, RoundingMode.HALF_EVEN));
        assertEquals(new BigDecimal("17.4721"), value.upper().setScale(4, RoundingMode.HALF_EVEN));
        assertEquals(new BigDecimal("8.5279"), value.lower().setScale(4, RoundingMode.HALF_EVEN));
        // width = (upper - lower) / middle * 100 = 4*sqrt(5)/13*100
        assertEquals(new BigDecimal("68.8021"), value.width().setScale(4, RoundingMode.HALF_EVEN));
        // %B = (16 - lower) / (upper - lower) = (3 + 2*sqrt(5)) / (4*sqrt(5))
        assertEquals(new BigDecimal("0.8354"), value.percentB().setScale(4, RoundingMode.HALF_EVEN));
    }

    @Test
    public void flatWindowCollapsesBandsWithoutDividingByZero() {
        BollingerBands bands = new BollingerBands(3, new BigDecimal("2.0"));

        bands.update(new BigDecimal("100"));
        bands.update(new BigDecimal("100"));
        Optional<BollingerBands.BollingerValue> result = bands.update(new BigDecimal("100"));

        BollingerBands.BollingerValue value = result.orElseThrow();
        assertEquals(0, value.upper().compareTo(value.lower()));
        assertEquals(0, value.width().compareTo(BigDecimal.ZERO));
        // %B is undefined on collapsed bands - reported as the midpoint instead of NaN/exception
        assertEquals(0, value.percentB().compareTo(new BigDecimal("0.5")));
    }

    @Test
    public void zeroPricedWindowGivesZeroWidth() {
        BollingerBands bands = new BollingerBands(2, new BigDecimal("2.0"));

        bands.update(BigDecimal.ZERO);
        Optional<BollingerBands.BollingerValue> result = bands.update(BigDecimal.ZERO);

        assertEquals(0, result.orElseThrow().width().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void slidesWindowKeepingOnlyThePeriodMostRecentCloses() {
        BollingerBands bands = new BollingerBands(3, new BigDecimal("1.0"));

        bands.update(new BigDecimal("1"));
        bands.update(new BigDecimal("1"));
        bands.update(new BigDecimal("1"));
        // The 1s must fall out of the window, leaving a flat 10/10/10
        bands.update(new BigDecimal("10"));
        bands.update(new BigDecimal("10"));
        Optional<BollingerBands.BollingerValue> result = bands.update(new BigDecimal("10"));

        BollingerBands.BollingerValue value = result.orElseThrow();
        assertEquals(0, value.middle().compareTo(BigDecimal.TEN));
        assertEquals(0, value.width().compareTo(BigDecimal.ZERO));
    }
}
