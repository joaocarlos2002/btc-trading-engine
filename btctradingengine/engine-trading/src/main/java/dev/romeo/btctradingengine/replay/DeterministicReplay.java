package dev.romeo.btctradingengine.replay;

import dev.romeo.btctradingengine.adapter.CandleAggregator;
import dev.romeo.btctradingengine.feature.DerivativesLookup;
import dev.romeo.btctradingengine.feature.FeatureExtractor;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.feature.OrderBookLookup;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.port.MarketDataPort;
import dev.romeo.btctradingengine.prediction.PredictionSettings;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.RuleBasedPredictor;
import dev.romeo.btctradingengine.trading.Position;
import dev.romeo.btctradingengine.trading.PositionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Replays recorded ticks through the live decision pipeline (issue #111): CandleAggregator, then
 * FeatureExtractor, then {@link RuleBasedPredictor#withLiveRules}, then a simulated PositionManager, wired
 * in the same order as Main, with a {@link SimulatedClock} set to each tick's time. Nothing reads the
 * wall clock or the network, so the same ticks always give the same decisions, which is what makes
 * a live bug reproducible.
 *
 * <p>Not replayed: derivatives and order book snapshots (their lookups are empty) and the indicator
 * warmup from Binance history, so the first candles of a replay have no signals.
 */
public class DeterministicReplay {

    /** What the live bot is configured with; the application builds it from the same properties as the live pipeline. */
    public record Settings(Duration candleInterval, BigDecimal largeTradeNotional, IndicatorPeriods periods,
                           PredictionSettings prediction, BigDecimal targetPercent, BigDecimal stopLossPercent,
                           boolean allowShort) {
    }

    public record Result(List<PositionManager.ExecutionEvent> executionLog, List<Position> closedPositions,
                         Optional<Position> openPosition, List<PredictionVector> predictions, int candles, int ticks) {
    }

    private final Settings settings;

    public DeterministicReplay(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Result run(MarketDataPort source) {
        SimulatedClock clock = new SimulatedClock(Instant.EPOCH);
        PositionManager positions = new PositionManager(settings.targetPercent(), settings.stopLossPercent());
        positions.setAllowShort(settings.allowShort());
        positions.setClock(clock);
        // Simulation sends no orders; a same-thread executor keeps any future I/O path deterministic too
        positions.setOrderIoExecutor(Runnable::run);

        List<PredictionVector> predictions = new ArrayList<>();
        CandleEvent[] lastCandle = new CandleEvent[1];
        int[] counts = new int[2];

        RuleBasedPredictor predictor = RuleBasedPredictor.withLiveRules(prediction -> {
            predictions.add(prediction);
            if (lastCandle[0] != null) {
                positions.processPrediction(prediction, lastCandle[0]);
            }
        }, settings.periods(), settings.prediction());
        FeatureExtractor features = new FeatureExtractor(settings.periods(), DerivativesLookup.NONE, OrderBookLookup.NONE,
                vector -> {
                    positions.updateAtr(vector.atrValue());
                    predictor.onEvent(vector);
                });
        // Never started: candles close on the next tick's bucket, not on the wall-clock timer
        CandleAggregator aggregator = new CandleAggregator(settings.candleInterval(), candle -> {
            counts[0]++;
            lastCandle[0] = candle;
            features.onEvent(candle);
        }, settings.largeTradeNotional());

        source.start(tick -> {
            counts[1]++;
            clock.advanceTo(tick.eventTimestamp());
            // Same subscriber order as the live PriceEventBus: aggregator first, then positions
            aggregator.onEvent(tick);
            positions.processPriceEvent(tick);
        });

        return new Result(positions.getExecutionLog(), positions.getClosedPositions(), positions.getOpenPosition(),
                List.copyOf(predictions), counts[0], counts[1]);
    }
}
