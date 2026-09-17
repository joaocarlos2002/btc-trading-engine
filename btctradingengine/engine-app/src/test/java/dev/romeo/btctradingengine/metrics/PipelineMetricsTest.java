package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.CandleAggregator;
import dev.romeo.btctradingengine.adapter.PriceEventBus;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.port.PriceEventListener;
import dev.romeo.btctradingengine.prediction.MarketRegime;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #104: bus subscriber, late trade and signal meters. */
class PipelineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics metrics = new PipelineMetrics(registry);

    @Test
    void busSubscriberQueueAndDropsAreTaggedBySubscriber() throws Exception {
        PriceEventBus bus = new PriceEventBus(10, 0);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstDelivered = new CountDownLatch(1);
        PriceEventListener slow = event -> {
            firstDelivered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        bus.subscribeDropOldest(slow, 2);
        metrics.bindPriceBusSubscriber(bus, "dashboard", slow);
        NormalizedPriceEvent tick = new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, Instant.EPOCH, Instant.EPOCH);
        try {
            bus.onEvent(tick);
            assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++) {
                bus.onEvent(tick); // the worker is stuck on the first: 2 queued, 3 dropped
            }

            assertEquals(2, registry.get("pricebus.queue.size").tag("subscriber", "dashboard").gauge().value());
            assertEquals(3, registry.get("pricebus.dropped").tag("subscriber", "dashboard").functionCounter().count());
        } finally {
            release.countDown();
            bus.close();
        }
    }

    @Test
    void lateTradesComeFromTheAggregator() {
        CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candle -> { }, BigDecimal.TEN);
        metrics.bindCandleAggregator(aggregator);
        Instant t = Instant.parse("2026-09-16T12:00:30Z");

        aggregator.onEvent(new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, t, t, BigDecimal.ONE));
        aggregator.onEvent(new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, t.plusSeconds(60), t, BigDecimal.ONE));
        aggregator.onEvent(new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, t, t, BigDecimal.ONE));

        assertEquals(1, registry.get("candles.late.trades").functionCounter().count());
    }

    @Test
    void signalsAreCountedBySignalAndRegime() {
        PredictionVector buy = EngineMetricsTest.prediction(Signal.BUY, true);
        PredictionVector holdInTrend = PredictionVector.builder()
                .instrument("BTCUSDT").timestamp(Instant.EPOCH).signal(Signal.HOLD)
                .probabilityUp(new BigDecimal("0.5")).probabilityDown(new BigDecimal("0.5"))
                .confidence(BigDecimal.ZERO).price(BigDecimal.ONE).modelVersion("test")
                .marketRegime(MarketRegime.TREND_UP).reason("test").build();

        metrics.recordPrediction(buy);
        metrics.recordPrediction(buy);
        metrics.recordPrediction(holdInTrend);

        assertEquals(2, registry.get("prediction.signal").tags("signal", "BUY", "regime", "UNKNOWN").counter().count());
        assertEquals(1, registry.get("prediction.signal").tags("signal", "HOLD", "regime", "TREND_UP").counter().count());
    }

    @Test
    void noneIgnoresEverything() {
        assertDoesNotThrow(() -> {
            PipelineMetrics.NONE.recordPrediction(EngineMetricsTest.prediction(Signal.BUY, true));
            PipelineMetrics.NONE.bindCandleAggregator(null);
            PipelineMetrics.NONE.bindPriceBusSubscriber(null, "x", null);
        });
    }
}
