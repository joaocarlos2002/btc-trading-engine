package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Derivatives layer (issue #53): readings from the BTCUSDT USD-M perpetual, used as context for the
 * spot market the bot trades.
 *
 * Every component is null when that reading is not available - never 0, because a funding rate, a
 * basis or an open interest change of 0 is a real, meaningful value. Live, everything comes from the
 * futures REST API. In backtests funding and basis come from the REST history, and open interest and
 * long/short from the daily data.binance.vision metrics dumps (issue #54), since the REST keeps only
 * 30 days of those.
 *
 * The group itself is never null on a FeatureVector - FeatureExtractor always attaches one - because
 * a null group would turn every features.deriv().openInterest() into an NPE.
 */
public record DerivFeatures(
        BigDecimal openInterest,              // open contracts in BTC
        BigDecimal fundingRate,               // last settled funding rate, e.g. 0.0001 = 0.01% per 8h
        BigDecimal basisPercent,              // (perpetual close - spot close) / spot close * 100
        BigDecimal openInterestChangePercent, // open interest vs derivatives.open.interest.change.minutes ago
        BigDecimal longShortRatio             // global long/short account ratio
) {

    public static DerivFeatures empty() {
        return new DerivFeatures(null, null, null, null, null);
    }

    public boolean hasData() {
        return openInterest != null || fundingRate != null || basisPercent != null
                || openInterestChangePercent != null || longShortRatio != null;
    }
}
