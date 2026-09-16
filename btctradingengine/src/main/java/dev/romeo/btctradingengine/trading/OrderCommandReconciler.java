package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Startup resolution of the order outbox (issue #111). A PENDING or SENT command means the bot stopped
 * between recording an order and learning its outcome. Each one is looked up on Binance by its
 * clientOrderId and marked CONFIRMED or FAILED; nothing is ever sent again from here, since a blind
 * resend could buy or sell twice. Commands Binance cannot answer for stay unresolved and are reported.
 */
public class OrderCommandReconciler {
    private static final Logger logger = LoggerFactory.getLogger(OrderCommandReconciler.class);
    private static final Set<String> WORKING = Set.of("NEW", "PARTIALLY_FILLED", "PENDING_NEW");

    private final BinanceOrderExecutor executor;
    private final OrderCommandStore store;

    public OrderCommandReconciler(BinanceOrderExecutor executor, OrderCommandStore store) {
        this.executor = executor;
        this.store = store;
    }

    public enum Outcome { CONFIRMED, FAILED, UNRESOLVED }

    /** {@code order} is Binance's view of an ENTRY/EXIT order when it was found. */
    public record Resolution(OrderCommand command, Outcome outcome, Optional<BinanceOrderExecutor.QueriedOrder> order,
                             String detail) {
        public boolean filled() {
            return order.map(o -> "FILLED".equals(o.status())).orElse(false);
        }
    }

    public List<Resolution> resolve(String symbol) {
        List<Resolution> resolutions = new ArrayList<>();
        for (OrderCommand command : store.findUnresolved()) {
            Resolution resolution = switch (command.type()) {
                case ENTRY, EXIT -> resolveOrder(symbol, command);
                case OCO -> resolveOrderList(command, command.clientOrderId(), false);
                case CANCEL -> resolveOrderList(command, command.clientOrderId().substring("cancel:".length()), true);
            };
            switch (resolution.outcome()) {
                case CONFIRMED -> store.markConfirmed(command.clientOrderId());
                case FAILED -> store.markFailed(command.clientOrderId(), resolution.detail());
                case UNRESOLVED -> { }
            }
            logger.warn("Outbox {} {} ({}) was {}: {} - {}", command.type(), command.clientOrderId(),
                    command.positionId(), command.status(), resolution.outcome(), resolution.detail());
            resolutions.add(resolution);
        }
        return resolutions;
    }

    private Resolution resolveOrder(String symbol, OrderCommand command) {
        BinanceOrderExecutor.OrderLookup lookup = executor.lookupOrder(symbol, command.clientOrderId());
        switch (lookup.state()) {
            case NOT_FOUND -> {
                return new Resolution(command, Outcome.FAILED, Optional.empty(), "unknown to Binance: never accepted");
            }
            case ERROR -> {
                return new Resolution(command, Outcome.UNRESOLVED, Optional.empty(), "lookup failed: " + lookup.error());
            }
            default -> {
                BinanceOrderExecutor.QueriedOrder order = lookup.order().orElseThrow();
                if (WORKING.contains(order.status())) {
                    return new Resolution(command, Outcome.UNRESOLVED, lookup.order(), "still working: " + order.status());
                }
                boolean executed = order.executedQuantity() != null && order.executedQuantity().compareTo(BigDecimal.ZERO) > 0;
                return new Resolution(command, executed ? Outcome.CONFIRMED : Outcome.FAILED, lookup.order(),
                        order.status() + " executedQty=" + order.executedQuantity());
            }
        }
    }

    private Resolution resolveOrderList(OrderCommand command, String listClientOrderId, boolean cancel) {
        BinanceOrderExecutor.OrderListQuery list = executor.queryOrderList(listClientOrderId);
        if (list.state() == BinanceOrderExecutor.OrderListQuery.State.ERROR) {
            return new Resolution(command, Outcome.UNRESOLVED, Optional.empty(), "lookup failed: " + list.error());
        }
        if (cancel) {
            // The cancel worked unless the list is still executing
            return list.isExecuting()
                    ? new Resolution(command, Outcome.FAILED, Optional.empty(), "list still executing")
                    : new Resolution(command, Outcome.CONFIRMED, Optional.empty(), "list " + list.state() + " " + list.listOrderStatus());
        }
        return list.state() == BinanceOrderExecutor.OrderListQuery.State.FOUND
                ? new Resolution(command, Outcome.CONFIRMED, Optional.empty(), "list " + list.listOrderStatus())
                : new Resolution(command, Outcome.FAILED, Optional.empty(), "unknown to Binance: never accepted");
    }
}
