package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Derivatives layer, reserved for issue #9: open interest and funding rate need periodic REST
 * calls against the futures endpoints, so nothing populates them yet.
 *
 * The group is declared now so the FeatureVector record does not have to be reshaped again once
 * that data source exists. The group itself is never null on a FeatureVector - FeatureExtractor
 * always attaches empty() - because a null group would turn every future
 * features.deriv().openInterest() into an NPE and would serialize as a null branch in the
 * dashboard payload. The components are null while no data source exists, and hasData() reports
 * on exactly that.
 */
public record DerivFeatures(
        BigDecimal openInterest,          // issue #9 - null until the futures REST poller exists
        BigDecimal fundingRate            // issue #9 - null until the futures REST poller exists
) {

    public static DerivFeatures empty() {
        return new DerivFeatures(null, null);
    }

    public boolean hasData() {
        return openInterest != null || fundingRate != null;
    }
}
