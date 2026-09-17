package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;

/**
 * Decides whether a NEW position may be opened in a direction. Guards never turn a signal into HOLD:
 * the same prediction also closes an opposite position on signal reversal, and that exit must still
 * happen - RuleBasedPredictor only marks the prediction with entryAllowed=false.
 */
@FunctionalInterface
public interface EntryGuard {

    EntryGuard NONE = (features, signal) -> null;

    /** Why a new entry in this direction must not be opened, or null when it may. */
    String blockReason(FeatureVector features, Signal signal);

    /** The first guard that blocks wins; its reason is the one reported. */
    static EntryGuard allOf(EntryGuard... guards) {
        return (features, signal) -> {
            for (EntryGuard guard : guards) {
                String reason = guard.blockReason(features, signal);
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        };
    }
}
