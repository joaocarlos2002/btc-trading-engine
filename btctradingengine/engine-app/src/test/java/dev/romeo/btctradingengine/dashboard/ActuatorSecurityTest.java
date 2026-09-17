package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.Main;
import dev.romeo.btctradingengine.adapter.CandleAggregator;
import dev.romeo.btctradingengine.adapter.FeedStats;
import dev.romeo.btctradingengine.adapter.PriceEventBus;
import dev.romeo.btctradingengine.http.HttpMetrics;
import dev.romeo.btctradingengine.metrics.EngineMetrics;
import dev.romeo.btctradingengine.metrics.PipelineMetrics;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.prediction.Signal;
import dev.romeo.btctradingengine.trading.PositionManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #104: /actuator/health is public without details, /actuator/prometheus and the other
 * endpoints need the dashboard login, and the scrape carries the engine's meters.
 *
 * <p>The pipeline is off, so the meters are bound here over standalone beans the way
 * MetricsConfiguration binds the real ones. {@code db.url} points at a closed port: the context must
 * start without opening the lazy connection pool (Boot's own db health indicator would).
 */
@SpringBootTest(classes = Main.class, properties = {
        "engine.pipeline.enabled=false",
        "db.url=jdbc:postgresql://127.0.0.1:1/none",
        "dashboard.auth.username=trader",
        "dashboard.auth.password=s3cret",
        "dashboard.auth.roles=VIEWER"
})
@AutoConfigureMockMvc
// Tests replace the Prometheus registry with a simple one unless asked
@AutoConfigureObservability(tracing = false)
@Import(ActuatorSecurityTest.StandaloneMeters.class)
class ActuatorSecurityTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StandaloneMeters {
        @Bean
        EngineMetrics testEngineMetrics() {
            return new EngineMetrics(new FeedStats(), new DatabaseWriter(null),
                    TickRetentionJob.create(mock(DataSource.class), 7, 1000, Duration.ofMinutes(60)),
                    new PositionManager(new BigDecimal("2.0"), new BigDecimal("1.5")), new BigDecimal("5"));
        }

        @Bean(destroyMethod = "close")
        PriceEventBus testPriceEventBus(MeterRegistry registry) {
            PriceEventBus bus = new PriceEventBus(10, 0);
            PipelineMetrics metrics = new PipelineMetrics(registry);
            CandleAggregator aggregator = new CandleAggregator(Duration.ofMinutes(1), candle -> { }, BigDecimal.TEN);
            bus.subscribe(aggregator);
            metrics.bindPriceBusSubscriber(bus, "aggregator", aggregator);
            metrics.bindCandleAggregator(aggregator);
            metrics.recordPrediction(PredictionVector.builder()
                    .instrument("BTCUSDT").timestamp(Instant.EPOCH).signal(Signal.BUY)
                    .probabilityUp(new BigDecimal("0.5")).probabilityDown(new BigDecimal("0.5"))
                    .confidence(BigDecimal.ZERO).price(BigDecimal.ONE).modelVersion("test").reason("test").build());
            return bus;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsPublicButHidesItsDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void healthShowsItsComponentsToALoggedInUser() throws Exception {
        mvc.perform(get("/actuator/health").with(httpBasic("trader", "s3cret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").exists())
                .andExpect(jsonPath("$.components.db").doesNotExist());
    }

    @Test
    void prometheusAndTheOtherEndpointsNeedTheLogin() throws Exception {
        // A plain 401 for scrapers, not a redirect to the login form
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(httpBasic("trader", "wrong"))).andExpect(status().isUnauthorized());
    }

    @Test
    void envAndOtherSensitiveEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env").with(httpBasic("trader", "s3cret"))).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/heapdump").with(httpBasic("trader", "s3cret"))).andExpect(status().isNotFound());
    }

    @Test
    void theScrapeCarriesTheEngineMeters() throws Exception {
        HttpMetrics.listener().onResponse("api.binance.com", "/api/v3/depth", 200, 7);

        mvc.perform(get("/actuator/prometheus").with(httpBasic("trader", "s3cret")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("feed_tick_lag_ms{")))
                .andExpect(content().string(containsString("feed_tick_age_ms{")))
                .andExpect(content().string(containsString("feed_ticks_total{")))
                .andExpect(content().string(containsString("candles_late_trades_total{")))
                .andExpect(content().string(containsString("pricebus_queue_size{")))
                .andExpect(content().string(containsString("subscriber=\"aggregator\"")))
                .andExpect(content().string(containsString("pricebus_dropped_total{")))
                .andExpect(content().string(containsString("dbwriter_queue_size{")))
                .andExpect(content().string(containsString("dbwriter_dropped_total{")))
                .andExpect(content().string(containsString("ticks_retention_deleted_total{")))
                .andExpect(content().string(containsString("binance_http_requests_total{")))
                .andExpect(content().string(containsString("endpoint=\"/api/v3/depth\"")))
                .andExpect(content().string(containsString("binance_weight_used{")))
                .andExpect(content().string(containsString("prediction_signal_total{")))
                .andExpect(content().string(containsString("prediction_entry_blocked_total{")))
                .andExpect(content().string(containsString("position_open{")))
                .andExpect(content().string(containsString("position_unrealized_pnl_pct{")))
                .andExpect(content().string(containsString("portfolio_drawdown_pct{")))
                .andExpect(content().string(containsString("portfolio_drawdown_max_pct{")))
                .andExpect(content().string(containsString("orders_exit_failures_total{")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes{")))
                .andExpect(content().string(containsString("application=\"btc-trading-engine\"")))
                .andExpect(content().string(not(containsString("hikaricp_"))));
    }
}
