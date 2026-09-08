package dev.romeo.btctradingengine.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.adapter.PriceEventListener;
import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import dev.romeo.btctradingengine.prediction.PredictionVector;
import dev.romeo.btctradingengine.trading.Position;
import dev.romeo.btctradingengine.trading.PositionManager;
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

    public DashboardState() {
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

    public void refreshPositions() {
        PositionManager manager = positionManager;
        if (manager != null) {
            pendingEvents.put("trades", new TradesPayload(manager.getOpenPosition().orElse(null), manager.getClosedPositions()));
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

        boolean opened = manager.openManualBuy(tick.price(), tick.eventTimestamp() != null ? tick.eventTimestamp() : Instant.now());
        if (!opened) {
            return new ManualBuyResult(false, "A position is already open or trading is blocked");
        }

        refreshPositions();
        return new ManualBuyResult(true, "Manual BUY opened");
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
        return new ManualBuyResult(true, "Position closed manually");
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
        List<Position> positions = positionManager.getClosedPositions();
        int from = Math.max(0, positions.size() - Math.max(1, Math.min(limit, 500)));
        return new ArrayList<>(positions.subList(from, positions.size()));
    }

    public Stats stats() {
        if (positionManager == null) return new Stats(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        List<Position> positions = positionManager.getClosedPositions();
        BigDecimal pnl = positionManager.getTotalPnL();
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        List<Double> returns = new ArrayList<>();
        for (Position position : positions) {
            BigDecimal tradePnl = position.getPnL();
            equity = equity.add(tradePnl);
            peak = peak.max(equity);
            maxDrawdown = maxDrawdown.max(peak.subtract(equity));
            returns.add(tradePnl.doubleValue());
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
        BigDecimal sharpe = positions.size() < 2 || variance == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(mean / Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal drawdownPercent = peak.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : maxDrawdown.divide(peak, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        return new Stats(positions.size(), positionManager.getWinTrades(), pnl, sharpe, drawdownPercent);
    }

    private void flushPendingEvents() {
        if (sessions.isEmpty() || pendingEvents.isEmpty()) {
            return;
        }
        Map<String, Object> events = new HashMap<>(pendingEvents);
        events.forEach((type, payload) -> {
            if (pendingEvents.remove(type, payload)) {
                broadcast(type, payload);
            }
        });
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
    public record TradesPayload(Position open, List<Position> closed) {}
    public record ManualBuyResult(boolean success, String message) {}

    @Override
    public void close() {
        publisher.shutdownNow();
        pendingEvents.clear();
    }
}
