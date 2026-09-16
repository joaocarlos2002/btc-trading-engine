package dev.romeo.btctradingengine.trading;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence of the order outbox. Implementations must never throw: a failed write is logged, and
 * the order still goes out, because refusing an exit over a database hiccup is worse than a missing
 * outbox row.
 */
public interface OrderCommandStore {

    /**
     * Records a command as PENDING before its request. The clientOrderId is unique: recording it again
     * (an exit retried after a failure) resets the same row to PENDING and counts the attempt.
     */
    void record(String clientOrderId, String positionId, OrderCommand.Type type, Map<String, String> payload);

    void markSent(String clientOrderId);

    void markConfirmed(String clientOrderId);

    void markFailed(String clientOrderId, String error);

    Optional<OrderCommand> find(String clientOrderId);

    /** PENDING and SENT commands, oldest first. */
    List<OrderCommand> findUnresolved();

    /** Used when no outbox is configured (simulation). */
    OrderCommandStore NONE = new OrderCommandStore() {
        @Override
        public void record(String clientOrderId, String positionId, OrderCommand.Type type, Map<String, String> payload) {
        }

        @Override
        public void markSent(String clientOrderId) {
        }

        @Override
        public void markConfirmed(String clientOrderId) {
        }

        @Override
        public void markFailed(String clientOrderId, String error) {
        }

        @Override
        public Optional<OrderCommand> find(String clientOrderId) {
            return Optional.empty();
        }

        @Override
        public List<OrderCommand> findUnresolved() {
            return List.of();
        }
    };
}
