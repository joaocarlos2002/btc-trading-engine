package dev.romeo.btctradingengine.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.port.PriceEventListener;
import dev.romeo.btctradingengine.config.TradingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.trading.ExitReason;
import dev.romeo.btctradingengine.trading.Position;
import dev.romeo.btctradingengine.trading.PositionManager;
import dev.romeo.btctradingengine.trading.PositionState;
import dev.romeo.btctradingengine.trading.OrderConfirmationManager;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Instant;

@Component
public class DashboardState implements PriceEventListener, AutoCloseable {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<CandleEvent> candles = new ArrayList<>();
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Object> pendingEvents = new ConcurrentHashMap<>();
    private final ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "DashboardPublisher");
        thread.setDaemon(true);
        return thread;
    });

    private volatile NormalizedPriceEvent latestTick;
    private volatile CandleEvent latestCandle;
    private volatile FeatureVector latestFeatures;
    private volatile PredictionVector latestPrediction;
    private volatile PositionManager positionManager;
    private volatile OrderConfirmationManager confirmationManager;
    private volatile PositionsVersion publishedPositionsVersion;

    static final int MAX_PUBLISHED_CLOSED = 50;
    /** Placeholder queued for the "position" event: the open position is read when the event is flushed. */
    private static final Object LIVE_POSITION = new Object();

    private final BigDecimal initialCapital;

    /** trading.initial.capital.usdt: the base of the equity curve and drawdown in {@link #stats()}. */
    @Autowired
    public DashboardState(TradingProperties trading) {
        this(trading.initialCapitalUsdt());
    }

    public DashboardState(BigDecimal initialCapital) {
        this.initialCapital = initialCapital;
        publisher.scheduleAtFixedRate(this::flushPendingEvents, 50, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onEvent(NormalizedPriceEvent event) {
        latestTick = event;
        pendingEvents.put("price", event);
    }

    public void onCandle(CandleEvent candle) {
        latestCandle = candle;
        synchronized (candles) {
            candles.add(candle);
            if (candles.size() > 500) {
                candles.remove(0);
            }
        }
        pendingEvents.put("candle", candle);
    }

    public void onFeatures(FeatureVector features) {
        latestFeatures = features;
        pendingEvents.put("metrics", features);
    }

    public void onPrediction(PredictionVector prediction) {
        latestPrediction = prediction;
        pendingEvents.put("prediction", prediction);
    }

    public void attachPositionManager(PositionManager manager) {
        positionManager = manager;
    }

    public void attachOrderConfirmationManager(OrderConfirmationManager manager) {
        confirmationManager = manager;
    }

    /**
     * Called on every tick (issue #85), so it must stay cheap. The closed trades list is only copied and
     * published as "trades" when the positions actually change (a position opened, closed or changed state),
     * and then only the last {@link #MAX_PUBLISHED_CLOSED} of them. Between changes, an open position's live
     * price and P&L go out as the small "position" event instead.
     */
    public void refreshPositions() {
        PositionManager manager = positionManager;
        if (manager == null) {
            return;
        }
        Position open = manager.getOpenPosition().orElse(null);
        PositionsVersion version = new PositionsVersion(manager.getClosedPositionCount(),
                open == null ? null : open.getPositionId(),
                open == null ? null : open.getState());
        if (!version.equals(publishedPositionsVersion)) {
            publishedPositionsVersion = version;
            pendingEvents.put("trades", manager.getRecentClosedPositions(MAX_PUBLISHED_CLOSED));
        } else if (open != null) {
            pendingEvents.put("position", LIVE_POSITION);
        }
    }

    public ManualBuyResult manualBuy() {
        PositionManager manager = positionManager;
        if (manager == null) {
            return new ManualBuyResult(false, "Position manager is not ready");
        }

        NormalizedPriceEvent tick = latestTick;
        if (tick == null || tick.price() == null || tick.price().compareTo(BigDecimal.ZERO) <= 0) {
            return new ManualBuyResult(false, "Waiting for a current market price");
        }

        PositionManager.ManualBuyResult result = manager.openManualBuy(
                tick.price(), tick.eventTimestamp() != null ? tick.eventTimestamp() : Instant.now());
        // Refresh either way: a failed real entry still adds a closed position
        refreshPositions();
        return new ManualBuyResult(result.opened(), result.message());
    }

    public ManualBuyResult manualClose() {
        PositionManager manager = positionManager;
        if (manager == null) {
            return new ManualBuyResult(false, "Position manager is not ready");
        }

        NormalizedPriceEvent tick = latestTick;
        if (tick == null || tick.price() == null || tick.price().compareTo(BigDecimal.ZERO) <= 0) {
            return new ManualBuyResult(false, "Waiting for a current market price");
        }

        boolean closed = manager.closeManualPosition(
                tick.price(), tick.eventTimestamp() != null ? tick.eventTimestamp() : Instant.now());
        if (!closed) {
            return new ManualBuyResult(false, "Position could not be closed");
        }

        refreshPositions();
        boolean exitPending = manager.getOpenPosition().isPresent();
        return new ManualBuyResult(true, exitPending ? "Exit order sent" : "Position closed manually");
    }

    public void addSession(WebSocketSession session) {
        sessions.add(session);
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
    }

    public Optional<CandleEvent> latestCandle() { return Optional.ofNullable(latestCandle); }
    public Optional<NormalizedPriceEvent> latestTick() { return Optional.ofNullable(latestTick); }
    public Optional<FeatureVector> latestFeatures() { return Optional.ofNullable(latestFeatures); }
    public Optional<PredictionVector> latestPrediction() { return Optional.ofNullable(latestPrediction); }

    public List<CandleEvent> candles(int limit) {
        synchronized (candles) {
            int from = Math.max(0, candles.size() - Math.max(1, Math.min(limit, 500)));
            return new ArrayList<>(candles.subList(from, candles.size()));
        }
    }

    public Optional<Position> openPosition() {
        return positionManager == null ? Optional.empty() : positionManager.getOpenPosition();
    }

    public List<Position> closedPositions(int limit) {
        if (positionManager == null) return List.of();
        return positionManager.getRecentClosedPositions(Math.max(1, Math.min(limit, 500)));
    }

    public Stats stats() {
        return stats(initialCapital);
    }

    /**
     * Position.getPnL() is per unit (price points), not USDT, so the equity curve compounds each trade's
     * return fraction (pnl / entry) on the initial capital, like BacktestReport. The peak starts at the
     * capital, so a run of only losses still reports a drawdown.
     */
    Stats stats(BigDecimal initialCapital) {
        if (positionManager == null) return new Stats(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        // Same filter as PositionManager's counters: failed entries are not trades
        List<Position> positions = positionManager.getClosedPositions().stream()
                .filter(ExitReason::isPerformanceTrade)
                .toList();
        BigDecimal pnl = positionManager.getTotalPnL();
        double equity = initialCapital.doubleValue();
        double peak = equity;
        double maxDrawdown = 0;
        List<Double> returns = new ArrayList<>();
        for (Position position : positions) {
            BigDecimal tradePnl = position.getPnL();
            if (position.getEntryPrice() != null && position.getEntryPrice().signum() > 0) {
                equity *= 1 + tradePnl.doubleValue() / position.getEntryPrice().doubleValue();
            }
            peak = Math.max(peak, equity);
            if (peak > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peak - equity) / peak);
            }
            returns.add(tradePnl.doubleValue());
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
        BigDecimal sharpe = positions.size() < 2 || variance == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(mean / Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal drawdownPercent = BigDecimal.valueOf(maxDrawdown * 100).setScale(6, RoundingMode.HALF_UP);
        return new Stats(positions.size(), positionManager.getWinTrades(), pnl, sharpe, drawdownPercent);
    }

    private void flushPendingEvents() {
        if (sessions.isEmpty() || pendingEvents.isEmpty()) {
            return;
        }
        Map<String, Object> events = new HashMap<>(pendingEvents);
        boolean tradesSent = false;
        for (Map.Entry<String, Object> event : events.entrySet()) {
            if (!"position".equals(event.getKey()) && pendingEvents.remove(event.getKey(), event.getValue())) {
                tradesSent |= "trades".equals(event.getKey());
                broadcast(event.getKey(), resolvePayload(event.getKey(), event.getValue()));
            }
        }
        // "trades" already carries the current open position, so a live update in the same flush is redundant
        if (events.containsKey("position") && pendingEvents.remove("position", LIVE_POSITION) && !tradesSent) {
            Object open = resolvePayload("position", LIVE_POSITION);
            if (open != null) {
                broadcast("position", open);
            }
        }
    }

    /** "trades" and "position" read the open position at send time, so a stale one is never broadcast. */
    @SuppressWarnings("unchecked")
    private Object resolvePayload(String type, Object payload) {
        PositionManager manager = positionManager;
        if ("trades".equals(type)) {
            Position open = manager == null ? null : manager.getOpenPosition().orElse(null);
            return new TradesPayload(open, (List<Position>) payload);
        }
        if ("position".equals(type)) {
            return manager == null ? null : manager.getOpenPosition().orElse(null);
        }
        return payload;
    }

    /** Test hook: the payload queued for {@code type}, resolved as it would be broadcast, or null. */
    Object pendingPayload(String type) {
        Object payload = pendingEvents.get(type);
        return payload == null ? null : resolvePayload(type, payload);
    }

    void clearPendingEvents() {
        pendingEvents.clear();
    }

    private void broadcast(String type, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(new MapPayload(type, payload));
        } catch (JsonProcessingException ignored) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                } catch (Exception ignored) {
                    sessions.remove(session);
                }
            } else {
                sessions.remove(session);
            }
        }
    }

    public record Stats(int totalTrades, int winningTrades, BigDecimal totalPnl,
                        BigDecimal sharpe, BigDecimal maxDrawdown) {}

    public record MapPayload(String type, Object data) {}
    private record PositionsVersion(int closedCount, String openId, PositionState openState) {}
    public record TradesPayload(Position open, List<Position> closed) {}
    public record ManualBuyResult(boolean success, String message) {}

    @Override
    public void close() {
        publisher.shutdownNow();
        pendingEvents.clear();
    }
}
