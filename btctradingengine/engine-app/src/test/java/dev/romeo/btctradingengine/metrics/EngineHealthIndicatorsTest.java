package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.FeedStats;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.trading.ConnectivityGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Issue #104: market data, User Data Stream and connectivity guard health. */
class EngineHealthIndicatorsTest {

    private final List<ConnectivityGuard> guards = new ArrayList<>();

    @AfterEach
    void shutdown() {
        guards.forEach(ConnectivityGuard::shutdown);
    }

    private ConnectivityGuard guard(Duration staleness, boolean trackUserDataStream) {
        ConnectivityGuard guard = new ConnectivityGuard(staleness, trackUserDataStream);
        guards.add(guard);
        return guard;
    }

    @Test
    void marketDataIsUpWithAFreshTickAndDownWhenDisconnected() {
        FeedStats feed = new FeedStats();
        Instant now = Instant.now();
        feed.record(new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, now.minusMillis(40), now));
        ConnectivityGuard guard = guard(Duration.ofMinutes(1), false);

        Health up = EngineHealthIndicators.marketData(feed, guard, Duration.ofMinutes(1)).health();
        assertEquals(Status.UP, up.getStatus());
        assertEquals(40L, up.getDetails().get("lastTickLagMs"));

        guard.onMarketDataStatus("closed");
        assertEquals(Status.DOWN, EngineHealthIndicators.marketData(feed, guard, Duration.ofMinutes(1)).health().getStatus());
    }

    @Test
    void marketDataIsDownWhenTheLastTickIsTooOld() {
        FeedStats feed = new FeedStats();
        Instant old = Instant.now().minusSeconds(120);
        feed.record(new NormalizedPriceEvent("BTCUSDT", BigDecimal.ONE, old, old));

        Health health = EngineHealthIndicators.marketData(feed, guard(Duration.ofMinutes(1), false),
                Duration.ofSeconds(60)).health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void userDataStreamFollowsItsReportedStatus() {
        ConnectivityGuard guard = guard(Duration.ofMinutes(1), true);
        assertEquals(Status.UP, EngineHealthIndicators.userDataStream(guard).health().getStatus());

        guard.onUserDataStreamStatus("disconnected");

        assertEquals(Status.DOWN, EngineHealthIndicators.userDataStream(guard).health().getStatus());
    }

    @Test
    void databaseIsDownWhenNoConnectionCanBeOpened() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("refused"));

        assertEquals(Status.DOWN, EngineHealthIndicators.database(dataSource).health().getStatus());
    }

    @Test
    void connectivityGuardIsDownWithTheReasonItBlocksEntries() {
        ConnectivityGuard guard = guard(Duration.ofMinutes(1), false);
        assertEquals(Status.UP, EngineHealthIndicators.connectivityGuard(guard).health().getStatus());

        guard.onMarketDataStatus("failed");
        Health health = EngineHealthIndicators.connectivityGuard(guard).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("market data stream disconnected", health.getDetails().get("reason"));
    }
}
