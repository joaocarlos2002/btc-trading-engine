package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;

import java.math.BigDecimal;

/**
 * Blocks new entries while VPIN is high (issue #13): very one-sided aggressor flow tends to run over
 * whoever provides liquidity against it, so it is a bad moment to open a position.
 *
 * It is a guard and not a filter rule on purpose. Filter rules are averaged, and AtrRule (up to
 * +0.2) and VolatilityRule (up to +0.1) can cancel a negative score, so a VPIN filter rule could not
 * guarantee the block. And it only marks the prediction instead of turning it into HOLD: the same
 * prediction also closes an opposite position on signal reversal, and that exit must still happen.
 */
public class VpinEntryGuard implements EntryGuard {
    private final boolean enabled;
    private final BigDecimal highThreshold;

    public VpinEntryGuard(boolean enabled, BigDecimal highThreshold) {
        this.enabled = enabled;
        this.highThreshold = highThreshold;
    }

    public static VpinEntryGuard disabled() {
        return new VpinEntryGuard(false, BigDecimal.ONE);
    }

    public boolean blocksEntry(FeatureVector features) {
        BigDecimal vpin = features.flow().vpin();
        // 0 means VPIN is still warming up: never block on missing data
        return enabled
                && vpin.compareTo(BigDecimal.ZERO) > 0
                && vpin.compareTo(highThreshold) >= 0;
    }

    /** Toxic flow is bad for either direction, so the signal does not matter. */
    @Override
    public String blockReason(FeatureVector features, Signal signal) {
        return blocksEntry(features) ? "VPIN=" + features.flow().vpin() : null;
    }
}
