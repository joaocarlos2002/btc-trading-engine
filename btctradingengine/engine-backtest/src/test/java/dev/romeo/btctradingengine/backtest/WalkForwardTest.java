package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkForwardTest {

    private static final Instant START = SyntheticCandles.START;
    private static final BigDecimal CAPITAL = BigDecimal.valueOf(100);

    private static WalkForward.Settings settings(int train, int test, int holdOut) {
        return new WalkForward.Settings(train, test, holdOut, WalkForward.Objective.PROFIT_FACTOR, 1, 48);
    }

    private static Instant day(int n) {
        return START.plus(Duration.ofDays(n));
    }

    @Test
    void foldsTileTheRangeBeforeTheHoldOut() {
        // 30 days: 5 held out, folds of 10 train + 5 test anchored at day 25 -> tests 20-25, 15-20, 10-15
        WalkForward.Plan plan = WalkForward.plan(START, day(30), settings(10, 5, 5));

        assertEquals(3, plan.folds().size());
        assertEquals(3, settings(10, 5, 5).expectedFolds(30));
        assertEquals(new WalkForward.Window(day(25), day(30)), plan.holdOut());

        List<WalkForward.Fold> folds = plan.folds();
        assertEquals(new WalkForward.Window(day(0), day(10)), folds.get(0).train());
        assertEquals(new WalkForward.Window(day(10), day(15)), folds.get(0).test());
        assertEquals(new WalkForward.Window(day(5), day(15)), folds.get(1).train());
        assertEquals(new WalkForward.Window(day(15), day(20)), folds.get(1).test());
        assertEquals(new WalkForward.Window(day(20), day(25)), folds.get(2).test());

        for (int i = 0; i < folds.size(); i++) {
            WalkForward.Fold fold = folds.get(i);
            assertEquals(i, fold.index());
            // Train ends exactly where test starts: adjacent, never overlapping
            assertEquals(fold.train().end(), fold.test().start());
            // Step = testDays, so consecutive test windows tile without overlap
            if (i > 0) assertEquals(folds.get(i - 1).test().end(), fold.test().start());
            // Nothing of the hold-out is in any fold
            assertFalse(fold.test().end().isAfter(plan.holdOut().start()));
        }
    }

    @Test
    void aRangeAFewMinutesShortOfTheDaysStillFitsTheFirstFold() {
        // What "the last 30 days" of klines looks like: starting at the first whole minute after now - 30d
        WalkForward.Plan plan = WalkForward.plan(START.plusSeconds(59), day(30), settings(20, 5, 0));
        assertEquals(2, plan.folds().size());
        assertNull(plan.holdOut());
    }

    @Test
    void noFoldWhenTheRangeIsTooShort() {
        assertTrue(WalkForward.plan(START, day(12), settings(10, 5, 0)).folds().isEmpty());
        assertEquals(0, settings(10, 5, 0).expectedFolds(12));
        assertFalse(settings(10, 5, 0).validate(12).isEmpty());
    }

    @Test
    void sliceUsesHalfOpenWindowsOnOpenTime() {
        List<CandleEvent> candles = SyntheticCandles.hourly(3);
        WalkForward.Window window = new WalkForward.Window(day(1), day(2));
        List<CandleEvent> slice = WalkForward.slice(candles, window);
        assertEquals(24, slice.size());
        assertEquals(day(1), slice.get(0).openTime());
        assertTrue(slice.get(23).openTime().isBefore(day(2)));

        List<CandleEvent> warmup = WalkForward.warmupBefore(candles, day(1), 5);
        assertEquals(5, warmup.size());
        assertEquals(day(1).minus(Duration.ofHours(1)), warmup.get(4).openTime());
        assertEquals(24, WalkForward.warmupBefore(candles, day(1), 1000).size());
    }

    @Test
    void selectionPicksTheBestQualifiedScore() {
        List<ParameterSweep.Result> results = List.of(
                new ParameterSweep.Result("0.1", report(0.05)),                        // PF 999 but 1 trade
                new ParameterSweep.Result("0.2", report(0.02, -0.01, 0.02)),           // PF 4
                new ParameterSweep.Result("0.3", report(0.01, -0.01, 0.01)),           // PF 2
                new ParameterSweep.Result("0.4", report(0.02, -0.01, 0.02)));          // PF 4, tie with 0.2

        List<WalkForward.Candidate> candidates = WalkForward.score(results, WalkForward.Objective.PROFIT_FACTOR, 3);
        assertFalse(candidates.get(0).qualified());
        assertEquals("0.2", WalkForward.select(candidates).orElseThrow().value(), "tie goes to the first listed value");

        List<WalkForward.Candidate> byReturn = WalkForward.score(results, WalkForward.Objective.RETURN_PERCENT, 1);
        assertEquals("0.1", WalkForward.select(byReturn).orElseThrow().value());

        assertTrue(WalkForward.select(WalkForward.score(results, WalkForward.Objective.SHARPE, 10)).isEmpty());
    }

    @Test
    void holdOutValueIsTheMostFrequentWithTiesToTheLatest() {
        assertEquals("a", WalkForward.holdOutValue(Arrays.asList("a", "b", "a", null)));
        assertEquals("a", WalkForward.holdOutValue(List.of("a", "b", "c", "b", "a")));
        assertEquals("b", WalkForward.holdOutValue(List.of("a", "b", "c", "a", "b")));
        assertEquals("a", WalkForward.holdOutValue(Arrays.asList("b", "a", "b", "a")));
        assertEquals("c", WalkForward.holdOutValue(List.of("a", "b", "c")));
        assertNull(WalkForward.holdOutValue(Arrays.asList(null, null)));
    }

    @Test
    void objectiveNamesParse() {
        assertEquals(WalkForward.Objective.PROFIT_FACTOR, WalkForward.Objective.parse(null).orElseThrow());
        assertEquals(WalkForward.Objective.PROFIT_FACTOR, WalkForward.Objective.parse("profitFactor").orElseThrow());
        assertEquals(WalkForward.Objective.RETURN_PERCENT, WalkForward.Objective.parse("returnPercent").orElseThrow());
        assertEquals(WalkForward.Objective.SHARPE, WalkForward.Objective.parse("SHARPE").orElseThrow());
        assertTrue(WalkForward.Objective.parse("winRate").isEmpty());
    }

    @Test
    void runsEveryFoldOutOfSampleAndTheHoldOutOnSyntheticCandles() throws Exception {
        List<CandleEvent> candles = SyntheticCandles.hourly(20);
        List<String> values = List.of("0.05", "0.3", "0.5");
        WalkForward.Settings settings = settings(6, 3, 5);
        WalkForward walkForward = new WalkForward(new ParameterSweep(new BacktestRunner(), null), new BacktestRunner());

        WalkForward.Result result = walkForward.run(candles, DerivativesLookup.NONE, SyntheticCandles.fastParams(),
                "buyThreshold", values, settings, CAPITAL);

        // 20 days - 5 hold-out = 15: tests at 12-15, 9-12, 6-9 (train 0-6 is the first that fits)
        assertEquals(3, result.folds().size());
        WalkForward.Window holdOut = result.holdOut().window();
        int oosTrades = 0;
        List<String> chosen = new ArrayList<>();
        for (WalkForward.FoldResult fold : result.folds()) {
            assertEquals(6 * 24, fold.trainCandles());
            assertEquals(3 * 24, fold.testCandles());
            assertEquals(values.size(), fold.candidates().size());
            assertTrue(fold.test().end().compareTo(holdOut.start()) <= 0);
            if (fold.chosenValue() != null) {
                assertEquals(WalkForward.select(fold.candidates()).orElseThrow().value(), fold.chosenValue());
                oosTrades += fold.testReport().getTotalTrades();
                for (Trade t : fold.testReport().trades()) {
                    assertTrue(fold.test().contains(t.getEntryTime()), "test trades stay in their window");
                }
            }
            chosen.add(fold.chosenValue());
        }
        assertEquals(oosTrades, result.outOfSample().getTotalTrades());
        assertEquals(5 * 24, result.holdOut().candles());
        assertEquals(WalkForward.holdOutValue(chosen), result.holdOut().chosenValue());
        assertNotNull(result.holdOut().report());
        for (Trade t : result.holdOut().report().trades()) {
            assertTrue(holdOut.contains(t.getEntryTime()));
        }
    }

    /** A report whose closed trades have exactly these net returns (commission zero). */
    private static BacktestReport report(double... returns) {
        List<Trade> trades = new ArrayList<>();
        for (int i = 0; i < returns.length; i++) {
            Trade trade = new Trade("T" + i, Signal.BUY, BigDecimal.valueOf(100), START);
            trade.close(BigDecimal.valueOf(100 * (1 + returns[i])), START.plusSeconds(60));
            trades.add(trade);
        }
        return new BacktestReport(trades, CAPITAL, BigDecimal.ZERO);
    }
}
