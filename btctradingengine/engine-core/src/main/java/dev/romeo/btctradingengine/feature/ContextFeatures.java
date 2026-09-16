package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Context layer: where price sits relative to its recent references. Context modulates conviction
 * and confirms what the score layer produced; on its own it produces no signal, which is why none
 * of these fields has a directional rule of its own.
 *
 * BigDecimal.ZERO means "not available yet" (indicator still warming up).
 */
public record ContextFeatures(
        BigDecimal vwap,                  // session (daily UTC) or rolling VWAP
        BigDecimal vwapDistance,          // (close - vwap) / vwap * 100
        BigDecimal bbPercentB,            // (close - lower) / (upper - lower), confirmation only
        BigDecimal donchianUpper,         // highest high of the channel
        BigDecimal donchianLower,         // lowest low of the channel
        BigDecimal donchianPosition       // (close - lower) / (upper - lower) [0-1]
) {

    public static ContextFeatures empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BigDecimal vwap = BigDecimal.ZERO;
        private BigDecimal vwapDistance = BigDecimal.ZERO;
        private BigDecimal bbPercentB = BigDecimal.ZERO;
        private BigDecimal donchianUpper = BigDecimal.ZERO;
        private BigDecimal donchianLower = BigDecimal.ZERO;
        private BigDecimal donchianPosition = BigDecimal.ZERO;

        public Builder vwap(BigDecimal vwap) { this.vwap = vwap; return this; }
        public Builder vwapDistance(BigDecimal vwapDistance) { this.vwapDistance = vwapDistance; return this; }
        public Builder bbPercentB(BigDecimal bbPercentB) { this.bbPercentB = bbPercentB; return this; }
        public Builder donchianUpper(BigDecimal donchianUpper) { this.donchianUpper = donchianUpper; return this; }
        public Builder donchianLower(BigDecimal donchianLower) { this.donchianLower = donchianLower; return this; }
        public Builder donchianPosition(BigDecimal donchianPosition) { this.donchianPosition = donchianPosition; return this; }

        public ContextFeatures build() {
            return new ContextFeatures(vwap, vwapDistance, bbPercentB, donchianUpper, donchianLower, donchianPosition);
        }
    }
}
