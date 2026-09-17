package dev.romeo.btctradingengine.resilience;

/**
 * How a request may be retried, whether its circuit breaker can refuse it, and how it competes for
 * request weight (issue #100).
 *
 * <p>Layering: this is the only per-request retry. Cross-tick exit retries stay in PositionManager
 * (issue #68) and order outcome recovery stays in the executor / OrderConfirmationManager / outbox, so
 * an order POST gets at most {@code maxAttempts} sends here, and only for failures that prove Binance
 * did not process it (429, or the connection never opened).
 */
public enum BinanceCall {
    /** Signed reads and cancels (account, open orders, order status, DELETE): retried on I/O errors, 429 and 5xx. */
    TRADING_READ(Retry.IDEMPOTENT, false, Priority.CRITICAL),
    /**
     * New orders (POST /api/v3/order, OCO): retried only when the request certainly did not reach Binance -
     * a 429 or a connect failure. A timeout or a dropped connection after sending is ambiguous and is left to
     * the clientOrderId lookup. The breaker records outcomes but never refuses the call, so exits and stops
     * always go out; the entry guard reads the breaker instead.
     */
    TRADING_WRITE(Retry.NOT_SENT_ONLY, false, Priority.CRITICAL),
    /** Single-shot requests (server time sync). */
    TRADING_ONCE(Retry.NONE, false, Priority.CRITICAL),
    /** Live market data polls and warmup: retried, refused while the breaker is open. */
    MARKET_DATA(Retry.IDEMPOTENT, true, Priority.NORMAL),
    /** Backtest history downloads: like market data, but they cannot use the budget share kept for the live bot. */
    HISTORY(Retry.IDEMPOTENT, true, Priority.BULK);

    enum Retry { IDEMPOTENT, NOT_SENT_ONLY, NONE }

    enum Priority {
        /** Never waits for weight, pauses or the breaker; only an active IP ban stops it. */
        CRITICAL,
        NORMAL,
        BULK
    }

    final Retry retry;
    final boolean breakerCanRefuse;
    final Priority priority;

    BinanceCall(Retry retry, boolean breakerCanRefuse, Priority priority) {
        this.retry = retry;
        this.breakerCanRefuse = breakerCanRefuse;
        this.priority = priority;
    }
}
