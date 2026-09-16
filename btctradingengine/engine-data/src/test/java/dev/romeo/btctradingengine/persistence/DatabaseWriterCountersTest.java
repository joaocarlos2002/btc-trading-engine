package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #104: queue size and drops of the DatabaseWriter are exposed for dbwriter.queue.size / dbwriter.dropped. */
class DatabaseWriterCountersTest {

    @Test
    void countsEventsDroppedWhenTheQueueIsFull() {
        // Never started, so nothing drains the queue
        DatabaseWriter writer = new DatabaseWriter(null);
        NormalizedPriceEvent tick = new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, Instant.EPOCH, Instant.EPOCH);

        for (int i = 0; i < 10_005; i++) {
            writer.onEvent(tick);
        }

        assertEquals(10_000, writer.queueSize());
        assertEquals(5, writer.droppedEvents());
    }
}
