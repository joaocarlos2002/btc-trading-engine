package dev.romeo.btctradingengine.trading;

import java.util.Locale;

/**
 * Why a position was closed. Persisted by name in trades.exit_reason (VARCHAR(50)), so names must
 * stay stable: rows written before this enum only hold TARGET_HIT, STOP_LOSS and MANUAL_CLOSE.
 */
public enum ExitReason {
    TARGET_HIT(true),
    STOP_LOSS(true),
    SIGNAL_REVERSAL(true),
    MANUAL_CLOSE(true),
    // The entry never executed (P&L 0, no fill): not a trade, so kept out of performance metrics
    ORDER_FAILED(false),
    VALIDATION_FAILED(false),
    ERROR(false);

    private final boolean countsInPerformance;

    ExitReason(boolean countsInPerformance) {
        this.countsInPerformance = countsInPerformance;
    }

    public boolean countsInPerformance() {
        return countsInPerformance;
    }

    /** Null for a missing or unknown value, so one odd row never breaks loading the history. */
    public static ExitReason parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether a closed position is a real trade; legacy rows without a reason still count. */
    public static boolean isPerformanceTrade(Position position) {
        ExitReason reason = position.getExitReason();
        return reason == null || reason.countsInPerformance();
    }
}
