package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.port.ClockPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Outbox kept in memory: tests, and replays that must not touch the database. */
public class InMemoryOrderCommandStore implements OrderCommandStore {
    private final Map<String, OrderCommand> commands = new LinkedHashMap<>();
    private final ClockPort clock;
    private long nextId = 1;

    public InMemoryOrderCommandStore() {
        this(ClockPort.system());
    }

    public InMemoryOrderCommandStore(ClockPort clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void record(String clientOrderId, String positionId, OrderCommand.Type type,
                                    Map<String, String> payload) {
        Instant now = clock.now();
        OrderCommand existing = commands.get(clientOrderId);
        if (existing == null) {
            commands.put(clientOrderId, new OrderCommand(nextId++, clientOrderId, positionId, type, payload,
                    OrderCommand.Status.PENDING, 1, now, now, null));
        } else {
            commands.put(clientOrderId, new OrderCommand(existing.id(), clientOrderId, positionId, type, payload,
                    OrderCommand.Status.PENDING, existing.attempts() + 1, existing.createdAt(), now, null));
        }
    }

    @Override
    public void markSent(String clientOrderId) {
        update(clientOrderId, OrderCommand.Status.SENT, null);
    }

    @Override
    public void markConfirmed(String clientOrderId) {
        update(clientOrderId, OrderCommand.Status.CONFIRMED, null);
    }

    @Override
    public void markFailed(String clientOrderId, String error) {
        update(clientOrderId, OrderCommand.Status.FAILED, error);
    }

    private synchronized void update(String clientOrderId, OrderCommand.Status status, String error) {
        OrderCommand existing = commands.get(clientOrderId);
        if (existing != null) {
            commands.put(clientOrderId, existing.withStatus(status, clock.now(), error));
        }
    }

    @Override
    public synchronized Optional<OrderCommand> find(String clientOrderId) {
        return Optional.ofNullable(commands.get(clientOrderId));
    }

    @Override
    public synchronized List<OrderCommand> findUnresolved() {
        return commands.values().stream()
                .filter(command -> !command.status().isResolved())
                .sorted(Comparator.comparingLong(OrderCommand::id))
                .toList();
    }

    public synchronized List<OrderCommand> all() {
        return new ArrayList<>(commands.values());
    }
}
