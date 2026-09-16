package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.indicator.VwapAnchor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BacktestParamsValidationTest {

    private static final BacktestParams DEFAULTS = BacktestParams.fromConfig();

    @Test
    void configuredDefaultsAreValid() {
        assertEquals(List.of(), DEFAULTS.validate());
        assertEquals(List.of(), SyntheticCandles.fastParams().validate());
    }

    @Test
    void zeroSmaPeriodIsRejectedInsteadOfDividingByZero() {
        assertTrue(DEFAULTS.with("smaPeriod", 0).validate().contains("indicator.sma.period must be positive"));
    }

    @Test
    void macdFastMustBeBelowSlow() {
        BacktestParams equal = DEFAULTS.withOverrides(Map.of("macdFastPeriod", 26, "macdSlowPeriod", 26));
        BacktestParams above = DEFAULTS.withOverrides(Map.of("macdFastPeriod", 30, "macdSlowPeriod", 26));
        String expected = "indicator.macd.fast.period must be lower than slow.period";
        assertTrue(equal.validate().contains(expected));
        assertTrue(above.validate().contains(expected));
    }

    @Test
    void zeroConfirmationSnapshotsIsRejected() {
        assertTrue(DEFAULTS.with("confirmationSnapshots", 0).validate()
                .contains("prediction.confirmation.snapshots must be positive"));
    }

    @Test
    void everyErrorIsReportedAtOnce() {
        List<String> errors = DEFAULTS.withOverrides(Map.of(
                "smaPeriod", 0, "confirmationSnapshots", 0, "buyThreshold", 2, "commissionRate", -1)).validate();
        assertEquals(4, errors.size(), errors.toString());
    }

    @Test
    void hugePeriodsAreRejected() {
        assertFalse(DEFAULTS.with("vpinBuckets", BacktestParams.MAX_PERIOD + 1).validate().isEmpty());
    }

    @Test
    void withParsesEveryComponentType() {
        BacktestParams p = DEFAULTS
                .with("rsiPeriod", "21")
                .with("buyThreshold", "0.42")
                .with("bollingerStdDev", 2.5)
                .with("regimeGatingEnabled", "TRUE")
                .with("vwapAnchor", "rolling");
        assertEquals(21, p.rsiPeriod());
        assertEquals(0.42, p.buyThreshold());
        assertEquals(new BigDecimal("2.5"), p.bollingerStdDev());
        assertTrue(p.regimeGatingEnabled());
        assertEquals(VwapAnchor.ROLLING, p.vwapAnchor());
        // Nothing else changed
        assertEquals(DEFAULTS.smaPeriod(), p.smaPeriod());
    }

    @Test
    void unknownNamesAndUnparsableValuesAreReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DEFAULTS.withOverrides(Map.of("nope", 1, "smaPeriod", "abc")));
        assertTrue(e.getMessage().contains("unknown parameter 'nope'"), e.getMessage());
        assertTrue(e.getMessage().contains("smaPeriod"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.with("smaPeriod", "1.5"));
    }

    @Test
    void warmupCoversTheWidestWindow() {
        BacktestParams p = DEFAULTS.withOverrides(Map.of("vpinBuckets", 50, "vpinBucketCandles", 30, "smaPeriod", 10));
        assertTrue(p.warmupCandles() >= 1500);
    }
}
