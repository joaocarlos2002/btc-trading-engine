package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Regime layer: decides IF we trade at all and WHICH family of signal is valid right now
 * (breakout vs. mean-reversion). These fields belong in filter rules only - feeding them into the
 * directional score average would penalise range signals precisely in the range regime where they
 * are the valid ones (see the addFilterRule comment in RuleBasedPredictor).
 *
 * BigDecimal.ZERO means "not available yet" (indicator still warming up), the same convention the
 * existing rules already apply to atrValue.
 */
public record RegimeFeatures(
        BigDecimal adx,                   // ADX [0-100], trend strength
        BigDecimal plusDi,                // +DI [0-100]
        BigDecimal minusDi,               // -DI [0-100]
        BigDecimal atrPercent,            // ATR / close * 100
        BigDecimal bbWidth                // (upper - lower) / middle * 100, squeeze detector
) {

    public static RegimeFeatures empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BigDecimal adx = BigDecimal.ZERO;
        private BigDecimal plusDi = BigDecimal.ZERO;
        private BigDecimal minusDi = BigDecimal.ZERO;
        private BigDecimal atrPercent = BigDecimal.ZERO;
        private BigDecimal bbWidth = BigDecimal.ZERO;

        public Builder adx(BigDecimal adx) { this.adx = adx; return this; }
        public Builder plusDi(BigDecimal plusDi) { this.plusDi = plusDi; return this; }
        public Builder minusDi(BigDecimal minusDi) { this.minusDi = minusDi; return this; }
        public Builder atrPercent(BigDecimal atrPercent) { this.atrPercent = atrPercent; return this; }
        public Builder bbWidth(BigDecimal bbWidth) { this.bbWidth = bbWidth; return this; }

        public RegimeFeatures build() {
            return new RegimeFeatures(adx, plusDi, minusDi, atrPercent, bbWidth);
        }
    }
}
