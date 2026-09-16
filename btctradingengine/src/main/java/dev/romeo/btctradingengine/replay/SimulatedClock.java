package dev.romeo.btctradingengine.replay;

import dev.romeo.btctradingengine.port.ClockPort;

import java.time.Instant;

/** A clock that only moves when the replay moves it: the time of the tick being processed. */
public class SimulatedClock implements ClockPort {
    private volatile Instant now;

    public SimulatedClock(Instant start) {
        this.now = start;
    }

    @Override
    public Instant now() {
        return now;
    }

    /** Never goes backwards, so an out-of-order tick cannot rewind backoffs. */
    public void advanceTo(Instant time) {
        if (time != null && time.isAfter(now)) {
            now = time;
        }
    }
}
