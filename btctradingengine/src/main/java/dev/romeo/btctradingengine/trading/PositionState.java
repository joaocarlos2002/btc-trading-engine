package dev.romeo.btctradingengine.trading;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lifecycle of a position (issue #111). Explicit states rule out the combinations behind issues #63,
 * #68 and #99, e.g. a second exit order sent while the first one is still in flight.
 *
 * <pre>
 * PENDING_ENTRY --fill--------------> OPEN --exit sent--> EXIT_PENDING --fill--> CLOSED
 *       |                              |  ^                    |
 *       +--order failed--> FAILED <----+  +----exit failed-----+
 *                                      +--OCO filled / simulated close--> CLOSED
 * </pre>
 *
 * Persisted by name in trades.state, so names must stay stable.
 */
public enum PositionState {
    PENDING_ENTRY,
    OPEN,
    EXIT_PENDING,
    CLOSED,
    FAILED;

    private Set<PositionState> next() {
        return switch (this) {
            case PENDING_ENTRY -> EnumSet.of(OPEN, FAILED);
            // OPEN -> FAILED: an entry that Binance later reports as cancelled with nothing executed
            case OPEN -> EnumSet.of(EXIT_PENDING, CLOSED, FAILED);
            // EXIT_PENDING -> OPEN: the exit order failed and waits for the retry backoff
            case EXIT_PENDING -> EnumSet.of(OPEN, CLOSED);
            case CLOSED, FAILED -> EnumSet.noneOf(PositionState.class);
        };
    }

    public boolean canTransitionTo(PositionState target) {
        return target != null && next().contains(target);
    }

    public boolean isTerminal() {
        return this == CLOSED || this == FAILED;
    }

    /** Terminal state for a close: an entry that never executed is FAILED, not a closed trade. */
    public static PositionState terminalFor(ExitReason reason) {
        return reason != null && !reason.countsInPerformance() ? FAILED : CLOSED;
    }

    /** Null for a missing or unknown value, like {@link ExitReason#parse}. */
    public static PositionState parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(String positionId, PositionState from, PositionState to) {
            super("Illegal position state transition for " + positionId + ": " + from + " -> " + to);
        }
    }
}
