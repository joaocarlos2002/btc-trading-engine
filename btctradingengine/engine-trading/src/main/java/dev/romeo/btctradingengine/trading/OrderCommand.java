package dev.romeo.btctradingengine.trading;

import java.time.Instant;
import java.util.Map;

/**
 * One row of the order outbox (issue #111): every order the bot sends to Binance is recorded before
 * the request and updated after it, so a crash in between leaves a trace that startup
 * reconciliation resolves by clientOrderId instead of guessing or re-sending.
 */
public record OrderCommand(
        long id,
        String clientOrderId,
        String positionId,
        Type type,
        Map<String, String> payload,
        Status status,
        int attempts,
        Instant createdAt,
        Instant updatedAt,
        String lastError
) {
    /** ENTRY/EXIT are MARKET orders; OCO is the protection list; CANCEL cancels an OCO list. */
    public enum Type { ENTRY, EXIT, OCO, CANCEL }

    /**
     * PENDING: recorded, request not dispatched yet. SENT: request dispatched, outcome unknown.
     * CONFIRMED / FAILED: outcome known.
     */
    public enum Status {
        PENDING, SENT, CONFIRMED, FAILED;

        public boolean isResolved() {
            return this == CONFIRMED || this == FAILED;
        }
    }

    public OrderCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** The clientOrderId a CANCEL command is stored under; the list id itself belongs to the OCO row. */
    public static String cancelKey(String listClientOrderId) {
        return "cancel:" + listClientOrderId;
    }

    OrderCommand withStatus(Status newStatus, Instant at, String error) {
        return new OrderCommand(id, clientOrderId, positionId, type, payload, newStatus, attempts, createdAt, at, error);
    }
}
