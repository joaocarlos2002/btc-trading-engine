package dev.romeo.btctradingengine.replay;

import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.prediction.PredictionSettings;

import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #111: the same recorded ticks always produce the same decisions. */
public class DeterministicReplayTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int TICKS = 8_000;

    /** Oscillating trend with seeded noise, long enough to warm the live indicator periods and trade. */
    static List<NormalizedPriceEvent> fixture() {
        Random random = new Random(111);
        List<NormalizedPriceEvent> ticks = new ArrayList<>();
        double previous = 100_000;
        for (int i = 0; i < TICKS; i++) {
            double cycle = Math.sin(2 * Math.PI * i / 2_000.0) * 0.06 + Math.sin(2 * Math.PI * i / 450.0) * 0.015;
            double price = 100_000 * (1 + cycle) + random.nextGaussian() * 40;
            Instant time = START.plusSeconds(i * 30L);
            BigDecimal quantity = BigDecimal.valueOf(0.01 + random.nextDouble()).setScale(5, RoundingMode.HALF_UP);
            AggressorSide side = price >= previous ? AggressorSide.BUY : AggressorSide.SELL;
            ticks.add(new NormalizedPriceEvent("BTCUSDT", BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP),
                    time, time, quantity, side));
            previous = price;
        }
        return ticks;
    }

    private static DeterministicReplay replay() {
        return new DeterministicReplay(new DeterministicReplay.Settings(Duration.ofMinutes(1), new BigDecimal("100000"),
                IndicatorPeriods.defaults(), PredictionSettings.defaults(), new BigDecimal("2.0"), new BigDecimal("1.5"), false));
    }

    private static List<String> signals(List<PredictionVector> predictions) {
        return predictions.stream()
                .map(p -> p.timestamp() + " " + p.signal() + " " + p.probabilityUp() + " " + p.entryAllowed())
                .toList();
    }

    @Test
    public void twoRunsOverTheSameTicksMakeIdenticalDecisions() {
        List<NormalizedPriceEvent> ticks = fixture();

        DeterministicReplay.Result first = replay().run(new RecordedTicks(ticks));
        DeterministicReplay.Result second = replay().run(new RecordedTicks(ticks));

        assertEquals(TICKS, first.ticks());
        assertTrue(first.candles() > 2_000, "candles=" + first.candles());
        assertFalse(first.predictions().isEmpty(), "the pipeline must reach the predictor");
        assertFalse(first.executionLog().isEmpty(), "the fixture must trade for the comparison to mean something");
        assertEquals(first.executionLog(), second.executionLog());
        assertEquals(signals(first.predictions()), signals(second.predictions()));
        assertEquals(first.closedPositions().stream().map(p -> p.getPositionId() + p.getExitReason() + p.getExitPrice()).toList(),
                second.closedPositions().stream().map(p -> p.getPositionId() + p.getExitReason() + p.getExitPrice()).toList());
    }

    @Test
    public void decisionsUseTickTimeNotTheWallClock() {
        DeterministicReplay.Result result = replay().run(new RecordedTicks(fixture()));

        Instant end = START.plusSeconds(TICKS * 30L);
        result.executionLog().forEach(event ->
                assertTrue(!event.time().isBefore(START) && !event.time().isAfter(end), event.toString()));
    }
}
