package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Kill-switch por conectividade/staleness: bloqueia novas entradas quando o feed de
 * precos para de chegar ou os streams da Binance caem, independente do drawdown de P&amp;L.
 * Diferente de PortfolioManager.canTrade() (que reage a perdas), este guard reage a
 * ausencia/atraso de dados, que e o cenario em que o bot fica "cego" mesmo com posicao aberta.
 */
public class ConnectivityGuard {
    private static final Logger logger = LoggerFactory.getLogger(ConnectivityGuard.class);
    private static final long WATCHDOG_INTERVAL_SECONDS = 15;

    private final Duration maxStaleness;
    private final boolean trackUserDataStream;
    private final AtomicReference<Instant> lastPriceEventAt = new AtomicReference<>(Instant.now());
    private final ScheduledExecutorService watchdog;

    private volatile boolean marketDataConnected = true;
    private volatile boolean userDataStreamConnected = true;
    private volatile Supplier<Boolean> openPositionSupplier = () -> false;

    public ConnectivityGuard(Duration maxStaleness, boolean trackUserDataStream) {
        this.maxStaleness = maxStaleness;
        this.trackUserDataStream = trackUserDataStream;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ConnectivityGuardWatchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleAtFixedRate(this::checkHealth,
                WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void recordPriceEvent() {
        lastPriceEventAt.set(Instant.now());
    }

    public void onMarketDataStatus(String status) {
        marketDataConnected = isConnectedStatus(status);
    }

    public void onUserDataStreamStatus(String status) {
        userDataStreamConnected = isConnectedStatus(status);
    }

    public void setOpenPositionSupplier(Supplier<Boolean> supplier) {
        this.openPositionSupplier = supplier;
    }

    public boolean isStale() {
        return Duration.between(lastPriceEventAt.get(), Instant.now()).compareTo(maxStaleness) > 0;
    }

    public boolean isHealthy() {
        return !isStale() && marketDataConnected && (!trackUserDataStream || userDataStreamConnected);
    }

    public String getUnhealthyReason() {
        if (isStale()) {
            return "price feed stale (no data for > " + maxStaleness.toSeconds() + "s)";
        }
        if (!marketDataConnected) {
            return "market data stream disconnected";
        }
        if (trackUserDataStream && !userDataStreamConnected) {
            return "user data stream disconnected";
        }
        return "healthy";
    }

    private boolean isConnectedStatus(String status) {
        if (status == null) {
            return true;
        }
        String normalized = status.toLowerCase();
        return !normalized.equals("closed") && !normalized.equals("error") && !normalized.equals("failed")
                && !normalized.equals("disconnected");
    }

    private void checkHealth() {
        if (isHealthy()) {
            return;
        }
        if (Boolean.TRUE.equals(openPositionSupplier.get())) {
            logger.error("!!! CONNECTIVITY ALERT: {} while a position is OPEN - target/stop-loss monitoring may be blind !!!",
                    getUnhealthyReason());
        } else {
            logger.warn("Connectivity guard unhealthy: {} - blocking new entries until recovered", getUnhealthyReason());
        }
    }

    public void shutdown() {
        watchdog.shutdown();
    }
}
