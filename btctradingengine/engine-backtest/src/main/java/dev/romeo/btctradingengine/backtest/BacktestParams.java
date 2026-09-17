package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.prediction.PredictionSettings;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.indicator.VwapAnchor;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every strategy knob that can be varied for an on-demand backtest without touching global
 * Config (which is static/shared and could affect a concurrently running live bot). Covers
 * indicator periods (FeatureExtractor), each rule's internal sub-thresholds, the entry
 * threshold/confirmation (RuleBasedPredictor), and the risk/backtest settings (BacktestEngine).
 *
 * The periods stay flat here on purpose: these component names are the /backtest query parameter
 * names, so renaming or nesting them would break the HTTP contract. indicatorPeriods() converts
 * them into the grouped object FeatureExtractor takes.
 */
public record BacktestParams(
        int smaPeriod,
        int emaPeriod,
        int rsiPeriod,
        int atrPeriod,
        int macdFastPeriod,
        int macdSlowPeriod,
        int macdSignalPeriod,
        int volatilityShortPeriods,
        int volatilityLongPeriods,
        int volumeAveragePeriods,
        int adxPeriod,
        int bollingerPeriod,
        BigDecimal bollingerStdDev,
        int mfiPeriod,
        int donchianPeriod,
        VwapAnchor vwapAnchor,
        int vwapRollingPeriods,
        int priceActionLookback,
        int priceActionSwingStrength,
        int cvdPeriod,
        int emaSlopePeriods,
        int vpinBuckets,
        int vpinBucketCandles,
        BigDecimal absorptionDeltaMin,
        BigDecimal absorptionVolumeRatioMin,
        BigDecimal absorptionMaxMoveAtr,
        int absorptionWindow,

        BigDecimal rsiOversold,
        BigDecimal rsiNeutralLow,
        BigDecimal rsiNeutralHigh,
        BigDecimal rsiOverbought,
        BigDecimal smaDistanceExtreme,
        BigDecimal smaDistanceModerate,
        BigDecimal macdStrongHistogramAtrRatio,
        BigDecimal atrVolatilityLow,
        BigDecimal atrVolatilityNormal,
        BigDecimal atrVolatilityHigh,
        BigDecimal volatilityRatioHigh,
        BigDecimal mfiOversold,
        BigDecimal mfiNeutralLow,
        BigDecimal mfiNeutralHigh,
        BigDecimal mfiOverbought,
        BigDecimal adxTrendMin,
        BigDecimal bollingerSqueezeThreshold,
        BigDecimal adxTrendStrong,
        boolean regimeGatingEnabled,
        boolean vpinFilterEnabled,
        BigDecimal vpinHighThreshold,

        double buyThreshold,
        double sellThreshold,
        int confirmationSnapshots,

        boolean allowShort,
        BigDecimal targetPercent,
        BigDecimal stopLossPercent,
        BigDecimal commissionRate
) {
    /**
     * The live strategy as a backtest: indicator periods, prediction settings and risk from the same
     * configuration the live bot binds (issue #101).
     */
    public static BacktestParams of(IndicatorPeriods periods, PredictionSettings prediction, boolean allowShort,
                                    BigDecimal targetPercent, BigDecimal stopLossPercent, BigDecimal commissionRate) {
        return new BacktestParams(
                periods.sma(), periods.ema(), periods.rsi(), periods.atr(),
                periods.macdFast(), periods.macdSlow(), periods.macdSignal(),
                periods.volatilityShort(), periods.volatilityLong(), periods.volumeAverage(),
                periods.adx(), periods.bollinger(), periods.bollingerStdDev(),
                periods.mfi(), periods.donchian(),
                periods.vwapAnchor(), periods.vwapRollingPeriods(),
                periods.priceActionLookback(), periods.priceActionSwingStrength(),
                periods.cvd(), periods.emaSlope(),
                periods.vpinBuckets(), periods.vpinBucketCandles(),
                periods.absorptionDeltaMin(), periods.absorptionVolumeRatioMin(),
                periods.absorptionMaxMoveAtr(), periods.absorptionWindow(),

                prediction.rsiOversold(), prediction.rsiNeutralLow(), prediction.rsiNeutralHigh(), prediction.rsiOverbought(),
                prediction.smaDistanceExtreme(), prediction.smaDistanceModerate(),
                prediction.macdStrongHistogramAtrRatio(),
                prediction.atrVolatilityLow(), prediction.atrVolatilityNormal(), prediction.atrVolatilityHigh(),
                prediction.volatilityRatioHigh(),
                prediction.mfiOversold(), prediction.mfiNeutralLow(), prediction.mfiNeutralHigh(), prediction.mfiOverbought(),
                prediction.adxTrendMin(), prediction.bollingerSqueezeThreshold(), prediction.adxTrendStrong(),
                prediction.regimeGatingEnabled(), prediction.vpinFilterEnabled(), prediction.vpinHighThreshold(),

                prediction.buyThreshold(), prediction.sellThreshold(), prediction.confirmationSnapshots(),

                allowShort, targetPercent, stopLossPercent, commissionRate);
    }

    /** The shipped strategy (application.properties values); for tests and tools that run without the application. */
    public static BacktestParams defaults() {
        return of(IndicatorPeriods.defaults(), PredictionSettings.defaults(), false,
                new BigDecimal("2.0"), new BigDecimal("1.5"), new BigDecimal("0.001"));
    }

    public IndicatorPeriods indicatorPeriods() {
        return new IndicatorPeriods(
                smaPeriod, emaPeriod, rsiPeriod, atrPeriod,
                macdFastPeriod, macdSlowPeriod, macdSignalPeriod,
                volatilityShortPeriods, volatilityLongPeriods, volumeAveragePeriods,
                adxPeriod, bollingerPeriod, bollingerStdDev,
                mfiPeriod, donchianPeriod,
                vwapAnchor, vwapRollingPeriods,
                priceActionLookback, priceActionSwingStrength,
                cvdPeriod, emaSlopePeriods,
                vpinBuckets, vpinBucketCandles,
                absorptionDeltaMin, absorptionVolumeRatioMin,
                absorptionMaxMoveAtr, absorptionWindow);
    }

    /**
     * Largest period accepted for any window. Far above any sane strategy setting; it only stops a
     * request from allocating indicator buffers of millions of candles (issue #83).
     */
    public static final int MAX_PERIOD = 100_000;

    private static final RecordComponent[] COMPONENTS = BacktestParams.class.getRecordComponents();
    private static final Set<String> NAMES = Arrays.stream(COMPONENTS)
            .map(RecordComponent::getName)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Every problem with these params, empty when they can be run (issue #83). Mirrors the rules
     * the startup validation applies to the same settings, with the same property keys in the messages,
     * so a backtest never runs a combination the live bot would refuse to start with. Unlike Config
     * it collects every error instead of stopping at the first, so the HTTP 400 lists them all.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        positive(errors, "prediction.confirmation.snapshots", confirmationSnapshots);
        positive(errors, "indicator.sma.period", smaPeriod);
        positive(errors, "indicator.ema.period", emaPeriod);
        positive(errors, "indicator.rsi.period", rsiPeriod);
        positive(errors, "indicator.atr.period", atrPeriod);
        positive(errors, "indicator.macd.fast.period", macdFastPeriod);
        positive(errors, "indicator.macd.slow.period", macdSlowPeriod);
        positive(errors, "indicator.macd.signal.period", macdSignalPeriod);
        positive(errors, "feature.volatility.short.periods", volatilityShortPeriods);
        positive(errors, "feature.volatility.long.periods", volatilityLongPeriods);
        positive(errors, "feature.volume.average.periods", volumeAveragePeriods);
        positive(errors, "indicator.adx.period", adxPeriod);
        positive(errors, "indicator.bollinger.period", bollingerPeriod);
        positive(errors, "indicator.mfi.period", mfiPeriod);
        positive(errors, "indicator.donchian.period", donchianPeriod);
        positive(errors, "indicator.vwap.rolling.periods", vwapRollingPeriods);
        positive(errors, "indicator.priceaction.lookback", priceActionLookback);
        positive(errors, "indicator.priceaction.swing.strength", priceActionSwingStrength);
        positive(errors, "indicator.cvd.period", cvdPeriod);
        positive(errors, "indicator.ema.slope.periods", emaSlopePeriods);
        positive(errors, "indicator.vpin.buckets", vpinBuckets);
        positive(errors, "indicator.vpin.bucket.candles", vpinBucketCandles);
        positive(errors, "feature.absorption.window", absorptionWindow);

        List<String> missing = Arrays.stream(COMPONENTS)
                .filter(c -> !c.getType().isPrimitive() && componentValue(c) == null)
                .map(RecordComponent::getName)
                .toList();
        if (!missing.isEmpty()) {
            // The comparisons below would throw on a null, so stop at the list of what is missing
            errors.add("missing values for " + String.join(", ", missing));
            return errors;
        }

        if (absorptionDeltaMin.signum() <= 0 || absorptionDeltaMin.compareTo(BigDecimal.ONE) > 0) {
            errors.add("feature.absorption.delta.min must be in (0, 1]");
        }
        if (absorptionVolumeRatioMin.signum() <= 0 || absorptionMaxMoveAtr.signum() < 0) {
            errors.add("feature.absorption.volume.ratio.min must be positive and max.move.atr non-negative");
        }
        if (vpinHighThreshold.signum() <= 0 || vpinHighThreshold.compareTo(BigDecimal.ONE) > 0) {
            errors.add("prediction.vpin.high must be in (0, 1]");
        }
        if (macdFastPeriod >= macdSlowPeriod) {
            errors.add("indicator.macd.fast.period must be lower than slow.period");
        }
        if (bollingerStdDev.signum() <= 0) {
            errors.add("indicator.bollinger.stddev must be positive");
        }
        if (adxTrendMin.compareTo(adxTrendStrong) > 0) {
            errors.add("prediction.adx.trend.min cannot be above prediction.adx.trend.strong");
        }
        if (mfiOversold.compareTo(mfiOverbought) >= 0) {
            errors.add("prediction.mfi.oversold must be lower than prediction.mfi.overbought");
        }
        if (!(buyThreshold > 0 && buyThreshold <= 1 && sellThreshold < 0 && sellThreshold >= -1)) {
            errors.add("Prediction thresholds must be within [-1, 1] with buy positive and sell negative");
        }
        if (targetPercent.signum() <= 0 || stopLossPercent.signum() <= 0) {
            errors.add("Trading target and stop loss must be positive");
        }
        if (commissionRate.signum() < 0) {
            errors.add("backtest.commission.rate cannot be negative");
        }
        return errors;
    }

    private static void positive(List<String> errors, String key, int value) {
        if (value <= 0) {
            errors.add(key + " must be positive");
        } else if (value > MAX_PERIOD) {
            errors.add(key + " must be at most " + MAX_PERIOD);
        }
    }

    /**
     * Candles an extractor needs before every indicator these params configure has left warmup:
     * the widest window any indicator uses (MACD needs slow + signal, ADX double smoothing, VPIN
     * buckets x candles per bucket). Sizes the non-trading prefix of a walk-forward window.
     */
    public int warmupCandles() {
        long[] windows = {
                smaPeriod, (long) emaPeriod + emaSlopePeriods, rsiPeriod + 1L, atrPeriod + 1L,
                (long) macdSlowPeriod + macdSignalPeriod,
                volatilityShortPeriods, volatilityLongPeriods, volumeAveragePeriods,
                2L * adxPeriod, bollingerPeriod, mfiPeriod + 1L, donchianPeriod,
                vwapRollingPeriods, (long) priceActionLookback + priceActionSwingStrength, cvdPeriod,
                (long) vpinBuckets * vpinBucketCandles, absorptionWindow
        };
        long max = Arrays.stream(windows).max().orElse(0);
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    /** The names accepted by {@link #with} and {@link #withOverrides}: the record components. */
    public static Set<String> parameterNames() {
        return NAMES;
    }

    /**
     * A copy with one component replaced, parsed from its text form - the name-to-wither mapping the
     * sweep and walk-forward use to vary any field generically (issue #108).
     *
     * @throws IllegalArgumentException for an unknown name or a value that does not parse
     */
    public BacktestParams with(String name, Object value) {
        Map<String, Object> single = new LinkedHashMap<>();
        single.put(name, value);
        return withOverrides(single);
    }

    /**
     * A copy with every entry of {@code overrides} applied. Values may be strings or JSON scalars.
     *
     * @throws IllegalArgumentException listing every unknown name and unparsable value
     */
    public BacktestParams withOverrides(Map<String, ?> overrides) {
        Map<String, Object> replacements = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        overrides.forEach((name, raw) -> {
            RecordComponent component = Arrays.stream(COMPONENTS)
                    .filter(c -> c.getName().equals(name))
                    .findFirst().orElse(null);
            if (component == null) {
                errors.add("unknown parameter '" + name + "'");
                return;
            }
            try {
                replacements.put(name, parse(component.getType(), raw));
            } catch (RuntimeException e) {
                errors.add("invalid value '" + raw + "' for " + name + " (expected "
                        + component.getType().getSimpleName() + ")");
            }
        });
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        Object[] args = new Object[COMPONENTS.length];
        Class<?>[] types = new Class<?>[COMPONENTS.length];
        for (int i = 0; i < COMPONENTS.length; i++) {
            RecordComponent c = COMPONENTS[i];
            types[i] = c.getType();
            args[i] = replacements.containsKey(c.getName()) ? replacements.get(c.getName()) : componentValue(c);
        }
        try {
            // The canonical constructor takes the component types in order, which static analysis cannot see
            //noinspection JavaReflectionMemberAccess
            Constructor<BacktestParams> canonical = BacktestParams.class.getDeclaredConstructor(types);
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not copy BacktestParams", e);
        }
    }

    private Object componentValue(RecordComponent component) {
        try {
            return component.getAccessor().invoke(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read " + component.getName(), e);
        }
    }

    private static Object parse(Class<?> type, Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null");
        }
        String text = raw.toString().trim();
        if (type == int.class) {
            return new BigDecimal(text).intValueExact();
        }
        if (type == double.class) {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) throw new NumberFormatException(text);
            return value;
        }
        if (type == boolean.class) {
            if (text.equalsIgnoreCase("true")) return true;
            if (text.equalsIgnoreCase("false")) return false;
            throw new IllegalArgumentException(text);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(text);
        }
        if (type == VwapAnchor.class) {
            return VwapAnchor.valueOf(text.toUpperCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("unsupported type " + type);
    }
}
