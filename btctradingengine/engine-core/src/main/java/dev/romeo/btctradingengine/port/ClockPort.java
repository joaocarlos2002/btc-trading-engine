package dev.romeo.btctradingengine.port;

import java.time.Instant;

/**
 * Time source of the trading logic (issue #111). Live trading uses the system clock; tests and the
 * deterministic replay drive a simulated one, so backoffs and polls depend on the input, not on when
 * it runs.
 */
@FunctionalInterface
public interface ClockPort {
    Instant now();

    static ClockPort system() {
        return Instant::now;
    }
}
