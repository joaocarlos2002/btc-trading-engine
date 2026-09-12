package dev.romeo.btctradingengine.trading;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectivityGuardTest {

    @Test
    public void healthyByDefaultAfterCreation() {
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofSeconds(60), true);
        try {
            assertTrue(guard.isHealthy());
        } finally {
            guard.shutdown();
        }
    }

    @Test
    public void becomesStaleWhenNoPriceEventWithinThreshold() throws InterruptedException {
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofMillis(50), false);
        try {
            guard.recordPriceEvent();
            assertTrue(guard.isHealthy());
            Thread.sleep(100);
            assertFalse(guard.isHealthy());
            assertTrue(guard.getUnhealthyReason().contains("stale"));
        } finally {
            guard.shutdown();
        }
    }

    @Test
    public void unhealthyWhenMarketDataDisconnected() {
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofSeconds(60), false);
        try {
            guard.onMarketDataStatus("closed");
            assertFalse(guard.isHealthy());
            guard.onMarketDataStatus("connected");
            assertTrue(guard.isHealthy());
        } finally {
            guard.shutdown();
        }
    }

    @Test
    public void unhealthyWhenUserDataStreamDisconnectedAndTracked() {
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofSeconds(60), true);
        try {
            guard.onUserDataStreamStatus("error");
            assertFalse(guard.isHealthy());
        } finally {
            guard.shutdown();
        }
    }

    @Test
    public void ignoresUserDataStreamStatusWhenNotTracked() {
        ConnectivityGuard guard = new ConnectivityGuard(Duration.ofSeconds(60), false);
        try {
            guard.onUserDataStreamStatus("error");
            assertTrue(guard.isHealthy());
        } finally {
            guard.shutdown();
        }
    }
}
