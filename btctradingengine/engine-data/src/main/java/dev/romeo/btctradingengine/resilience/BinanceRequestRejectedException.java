package dev.romeo.btctradingengine.resilience;

import java.io.IOException;

/**
 * The request was refused locally and never sent (issue #100): an IP ban is active, the endpoint's circuit
 * breaker is open, or no request weight was available in time. Never retried. An IOException so existing
 * callers keep treating it as a failed request.
 */
public class BinanceRequestRejectedException extends IOException {
    public enum Reason { IP_BANNED, CIRCUIT_OPEN, RATE_LIMITED }

    private final Reason reason;

    public BinanceRequestRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
