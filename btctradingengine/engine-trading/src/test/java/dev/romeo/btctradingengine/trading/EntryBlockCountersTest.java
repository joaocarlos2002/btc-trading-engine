package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #104: each reason an automatic entry was not opened is counted for prediction.entry.blocked. */
class EntryBlockCountersTest {

    private static PositionManager manager() {
        return new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5"));
    }

    @Test
    void countsASellThatCannotOpenAShort() {
        PositionManager manager = manager();

        manager.processPrediction(prediction(Signal.SELL, true), candle());

        assertEquals(1, manager.entriesBlocked(PositionManager.EntryBlock.SHORT_DISABLED));
        assertEquals(0, manager.entriesBlocked(PositionManager.EntryBlock.FILTER));
    }

    @Test
    void countsAPredictionWhoseFiltersDisallowEntries() {
        PositionManager manager = manager();

        manager.processPrediction(prediction(Signal.BUY, false), candle());
        manager.processPrediction(prediction(Signal.BUY, false), candle());

        assertEquals(2, manager.entriesBlocked(PositionManager.EntryBlock.FILTER));
        assertTrue(manager.getOpenPosition().isEmpty());
    }

    @Test
    void countsAnUnhealthyConnectivityGuard() {
        PositionManager manager = manager();
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofMinutes(5), false);
        try {
            guard.onMarketDataStatus("disconnected");
            manager.setConnectivityGuard(guard);

            manager.processPrediction(prediction(Signal.BUY, true), candle());

            assertEquals(1, manager.entriesBlocked(PositionManager.EntryBlock.CONNECTIVITY));
            assertEquals("connectivity", PositionManager.EntryBlock.CONNECTIVITY.tag());
        } finally {
            guard.shutdown();
        }
    }

    @Test
    void anOpenedEntryIsNotCounted() {
        PositionManager manager = manager();

        manager.processPrediction(prediction(Signal.BUY, true), candle());

        assertTrue(manager.getOpenPosition().isPresent());
        for (PositionManager.EntryBlock block : PositionManager.EntryBlock.values()) {
            assertEquals(0, manager.entriesBlocked(block), block.name());
        }
    }

    private static PredictionVector prediction(Signal signal, boolean entryAllowed) {
        return PredictionVector.builder()
                .instrument("BTCUSDT")
                .timestamp(Instant.parse("2026-09-08T10:01:00Z"))
                .signal(signal)
                .probabilityUp(new BigDecimal("0.5"))
                .probabilityDown(new BigDecimal("0.5"))
                .confidence(new BigDecimal("0.5"))
                .price(new BigDecimal("100"))
                .modelVersion("test")
                .entryAllowed(entryAllowed)
                .reason("test")
                .build();
    }

    private static CandleEvent candle() {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        BigDecimal price = new BigDecimal("100");
        return new CandleEvent("BTCUSDT", openTime, openTime.plusSeconds(60), price, price, price, price,
                BigDecimal.ONE, 1);
    }
}
