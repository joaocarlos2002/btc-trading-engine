package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.prediction.rules.AdxRegimeRule;
import dev.romeo.btctradingengine.prediction.rules.AtrRule;
import dev.romeo.btctradingengine.prediction.rules.MacdRule;
import dev.romeo.btctradingengine.prediction.rules.MfiRule;
import dev.romeo.btctradingengine.prediction.rules.RsiRule;
import dev.romeo.btctradingengine.prediction.rules.SmaMomentumRule;
import dev.romeo.btctradingengine.prediction.rules.VolatilityRule;

import java.math.BigDecimal;

/**
 * Every setting the live predictor takes: rule thresholds, the regime classifier, the entry guards and the
 * entry threshold/confirmation (issue #101). The application binds it from {@code prediction.*}; the
 * predictor never reads configuration itself.
 */
public record PredictionSettings(
        BigDecimal rsiOversold, BigDecimal rsiNeutralLow, BigDecimal rsiNeutralHigh, BigDecimal rsiOverbought,
        BigDecimal smaDistanceExtreme, BigDecimal smaDistanceModerate,
        BigDecimal macdStrongHistogramAtrRatio,
        BigDecimal atrVolatilityLow, BigDecimal atrVolatilityNormal, BigDecimal atrVolatilityHigh,
        BigDecimal volatilityRatioHigh,
        BigDecimal adxTrendMin, BigDecimal adxTrendStrong,
        BigDecimal mfiOversold, BigDecimal mfiNeutralLow, BigDecimal mfiNeutralHigh, BigDecimal mfiOverbought,
        BigDecimal bollingerSqueezeThreshold,
        boolean regimeGatingEnabled,
        boolean vpinFilterEnabled, BigDecimal vpinHighThreshold,
        boolean orderBookFilterEnabled, BigDecimal orderBookBuyMin, BigDecimal orderBookSellMax,
        double buyThreshold, double sellThreshold, int confirmationSnapshots
) {
    /** The values application.properties ships; for tests and tools that run without the application. */
    public static PredictionSettings defaults() {
        return new PredictionSettings(
                new BigDecimal("30"), new BigDecimal("40"), new BigDecimal("60"), new BigDecimal("70"),
                new BigDecimal("3"), new BigDecimal("1"),
                new BigDecimal("0.20"),
                new BigDecimal("0.023"), new BigDecimal("0.060"), new BigDecimal("0.119"),
                new BigDecimal("1.5"),
                new BigDecimal("20"), new BigDecimal("25"),
                new BigDecimal("20"), new BigDecimal("40"), new BigDecimal("60"), new BigDecimal("80"),
                new BigDecimal("0.5"),
                false,
                false, new BigDecimal("0.35"),
                false, new BigDecimal("0.35"), new BigDecimal("0.65"),
                0.28, -0.28, 2);
    }

    public MarketRegimeClassifier regimeClassifier() {
        return new MarketRegimeClassifier(adxTrendMin, adxTrendStrong, bollingerSqueezeThreshold);
    }

    /** VPIN and order book guards; each one only blocks while enabled. */
    public EntryGuard entryGuard() {
        return EntryGuard.allOf(new VpinEntryGuard(vpinFilterEnabled, vpinHighThreshold),
                new OrderBookEntryGuard(orderBookFilterEnabled, orderBookBuyMin, orderBookSellMax));
    }

    /** The periods only label the rules, so a reason names the period that was actually computed (issue #94). */
    public RsiRule rsiRule(IndicatorPeriods periods) {
        return new RsiRule(periods.rsi(), rsiOversold, rsiNeutralLow, rsiNeutralHigh, rsiOverbought);
    }

    public SmaMomentumRule smaMomentumRule(IndicatorPeriods periods) {
        return new SmaMomentumRule(periods.sma(), smaDistanceExtreme, smaDistanceModerate);
    }

    public MacdRule macdRule() {
        return new MacdRule(macdStrongHistogramAtrRatio);
    }

    public MfiRule mfiRule(IndicatorPeriods periods) {
        return new MfiRule(periods.mfi(), mfiOversold, mfiNeutralLow, mfiNeutralHigh, mfiOverbought);
    }

    public AtrRule atrRule() {
        return new AtrRule(atrVolatilityLow, atrVolatilityNormal, atrVolatilityHigh);
    }

    public VolatilityRule volatilityRule() {
        return new VolatilityRule(volatilityRatioHigh);
    }

    public AdxRegimeRule adxRegimeRule() {
        return new AdxRegimeRule(adxTrendMin, bollingerSqueezeThreshold, regimeGatingEnabled);
    }
}
