package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.CandleAggregator;
import dev.romeo.btctradingengine.adapter.PriceEventBus;
import dev.romeo.btctradingengine.port.PriceEventListener;
import dev.romeo.btctradingengine.prediction.MarketRegime;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;

/**
 * Meters over the objects LivePipeline creates while it starts (issue #104): the bus subscribers, the
 * candle aggregator and the predictor's output. LivePipeline calls it; with no registry (tests) it is
 * {@link #NONE}.
 */
public class PipelineMetrics {
    public static final PipelineMetrics NONE = new PipelineMetrics(null);

    private final MeterRegistry registry;
    /** Built up front: one counter per signal and regime, so a prediction never builds a meter. */
    private final Map<Signal, Map<MarketRegime, Counter>> signals = new EnumMap<>(Signal.class);

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry == null) {
            return;
        }
        for (Signal signal : Signal.values()) {
            Map<MarketRegime, Counter> byRegime = new EnumMap<>(MarketRegime.class);
            for (MarketRegime regime : MarketRegime.values()) {
                byRegime.put(regime, Counter.builder("prediction.signal")
                        .description("Predictions emitted, by signal and market regime")
                        .tag("signal", signal.name())
                        .tag("regime", regime.name())
                        .register(registry));
            }
            signals.put(signal, byRegime);
        }
    }

    /** {@code pricebus.queue.size{subscriber}} and {@code pricebus.dropped{subscriber}} for one subscriber. */
    public void bindPriceBusSubscriber(PriceEventBus bus, String subscriber, PriceEventListener listener) {
        if (registry == null) {
            return;
        }
        Gauge.builder("pricebus.queue.size", bus, b -> b.queueSize(listener))
                .description("Ticks queued for a PriceEventBus subscriber")
                .tag("subscriber", subscriber)
                .strongReference(true)
                .register(registry);
        FunctionCounter.builder("pricebus.dropped", bus, b -> b.droppedEvents(listener))
                .description("Ticks a PriceEventBus subscriber lost to overflow (coalesced, for the dashboard)")
                .tag("subscriber", subscriber)
                .register(registry);
    }

    /** {@code candles.late.trades}: ticks dropped because their candle was already emitted. */
    public void bindCandleAggregator(CandleAggregator aggregator) {
        if (registry == null) {
            return;
        }
        FunctionCounter.builder("candles.late.trades", aggregator, CandleAggregator::getLateTicksDropped)
                .description("Trades that arrived after their candle was emitted and were dropped")
                .register(registry);
    }

    /** {@code prediction.signal{signal,regime}}. */
    public void recordPrediction(PredictionVector prediction) {
        if (registry == null) {
            return;
        }
        signals.get(prediction.signal()).get(prediction.marketRegime()).increment();
    }
}
