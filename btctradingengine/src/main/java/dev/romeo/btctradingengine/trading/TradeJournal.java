package dev.romeo.btctradingengine.trading;

import dev.romeo.btctradingengine.persistence.DataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.romeo.btctradingengine.prediction.Signal;

public class TradeJournal {
    private static final Logger logger = LoggerFactory.getLogger(TradeJournal.class);

    public void createTableIfNotExists() {
        String sql = """
                CREATE TABLE IF NOT EXISTS trades (
                    id BIGSERIAL PRIMARY KEY,
                    position_id VARCHAR(50) NOT NULL UNIQUE,
                    symbol VARCHAR(20) NOT NULL,
                    signal VARCHAR(10) NOT NULL,
                    entry_price NUMERIC(20, 8) NOT NULL,
                    entry_time TIMESTAMP WITH TIME ZONE NOT NULL,
                    exit_price NUMERIC(20, 8),
                    exit_time TIMESTAMP WITH TIME ZONE,
                    exit_reason VARCHAR(50),
                    pnl NUMERIC(20, 8),
                    pnl_percent NUMERIC(10, 6),
                    target_percent NUMERIC(10, 6) NOT NULL,
                    stop_loss_percent NUMERIC(10, 6) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );

                CREATE INDEX IF NOT EXISTS idx_trades_symbol ON trades(symbol);
                CREATE INDEX IF NOT EXISTS idx_trades_entry_time ON trades(entry_time DESC);
                CREATE INDEX IF NOT EXISTS idx_trades_status ON trades(exit_time);
                """;

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             var stmt = conn.createStatement()) {

            String[] statements = sql.split(";");
            for (String s : statements) {
                if (!s.trim().isEmpty()) {
                    stmt.execute(s.trim());
                }
            }
            logger.info("Trade journal table ready");
        } catch (SQLException e) {
            logger.error("Error creating trade journal table", e);
            throw new RuntimeException(e);
        }
    }

    public void recordTrade(Position position, String symbol) {
        String sql = """
                INSERT INTO trades
                (position_id, symbol, signal, entry_price, entry_time,
                 exit_price, exit_time, exit_reason, pnl, pnl_percent,
                 target_percent, stop_loss_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (position_id) DO UPDATE SET
                    entry_price = EXCLUDED.entry_price,
                    exit_price = EXCLUDED.exit_price,
                    exit_time = EXCLUDED.exit_time,
                    exit_reason = EXCLUDED.exit_reason,
                    pnl = EXCLUDED.pnl,
                    pnl_percent = EXCLUDED.pnl_percent
                """;

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, position.getPositionId());
            stmt.setString(2, symbol);
            stmt.setString(3, position.getSignal().toString());
            stmt.setBigDecimal(4, position.getEntryPrice());
            stmt.setTimestamp(5, java.sql.Timestamp.from(position.getEntryTime()));

            if (position.getExitPrice() != null) {
                stmt.setBigDecimal(6, position.getExitPrice());
                stmt.setTimestamp(7, java.sql.Timestamp.from(position.getExitTime()));
                stmt.setString(8, position.getExitReason());
                stmt.setBigDecimal(9, position.getPnL());
                stmt.setBigDecimal(10, position.getPnLPercent());
            } else {
                stmt.setNull(6, java.sql.Types.NUMERIC);
                stmt.setNull(7, java.sql.Types.TIMESTAMP);
                stmt.setNull(8, java.sql.Types.VARCHAR);
                stmt.setNull(9, java.sql.Types.NUMERIC);
                stmt.setNull(10, java.sql.Types.NUMERIC);
            }

            stmt.setBigDecimal(11, position.getTargetPercent());
            stmt.setBigDecimal(12, position.getStopLossPercent());

            stmt.executeUpdate();
            logger.debug("Trade recorded: {}", position.getPositionId());

        } catch (SQLException e) {
            logger.error("Error recording trade: {}", position.getPositionId(), e);
        }
    }

    public Optional<Position> loadOpenPosition(String symbol, BigDecimal targetPercent,
                                               BigDecimal stopLossPercent) {
        String sql = "SELECT position_id, signal, entry_price, entry_time "
                + "FROM trades WHERE symbol = ? AND exit_time IS NULL "
                + "ORDER BY entry_time DESC LIMIT 1";

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            try (var result = stmt.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }

                Position position = new Position(
                        result.getString("position_id"),
                        Signal.valueOf(result.getString("signal")),
                        result.getBigDecimal("entry_price"),
                        result.getTimestamp("entry_time").toInstant(),
                        targetPercent,
                        stopLossPercent
                );
                return Optional.of(position);
            }
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Error loading open position for {}", symbol, e);
            return Optional.empty();
        }
    }

    public List<Position> loadClosedPositions(String symbol, BigDecimal targetPercent,
                                              BigDecimal stopLossPercent, int limit) {
        String sql = "SELECT position_id, signal, entry_price, entry_time, exit_price, exit_time, exit_reason "
                + "FROM trades WHERE symbol = ? AND exit_time IS NOT NULL "
                + "ORDER BY exit_time DESC LIMIT ?";
        List<Position> positions = new ArrayList<>();

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setInt(2, Math.max(1, Math.min(limit, 5000)));
            try (var result = stmt.executeQuery()) {
                while (result.next()) {
                    Position position = new Position(
                            result.getString("position_id"),
                            Signal.valueOf(result.getString("signal")),
                            result.getBigDecimal("entry_price"),
                            result.getTimestamp("entry_time").toInstant(),
                            targetPercent,
                            stopLossPercent
                    );
                    position.restoreClosed(
                            result.getBigDecimal("exit_price"),
                            result.getTimestamp("exit_time").toInstant(),
                            result.getString("exit_reason")
                    );
                    positions.add(position);
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            logger.error("Error loading closed positions for {}", symbol, e);
        }

        java.util.Collections.reverse(positions);
        return positions;
    }

    public void recordExecutionLog(PositionManager.ExecutionEvent event, String symbol) {
        String sql = """
                INSERT INTO execution_log
                (position_id, symbol, action, signal, price, event_time, value)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, event.positionId());
            stmt.setString(2, symbol);
            stmt.setString(3, event.action());
            stmt.setString(4, event.signal().toString());
            stmt.setBigDecimal(5, event.price());
            stmt.setTimestamp(6, java.sql.Timestamp.from(event.time()));
            stmt.setDouble(7, event.value());

            stmt.executeUpdate();

        } catch (SQLException e) {
            logger.error("Error recording execution log", e);
        }
    }

    public void createExecutionLogTableIfNotExists() {
        String sql = """
                CREATE TABLE IF NOT EXISTS execution_log (
                    id BIGSERIAL PRIMARY KEY,
                    position_id VARCHAR(50) NOT NULL,
                    symbol VARCHAR(20) NOT NULL,
                    action VARCHAR(20) NOT NULL,
                    signal VARCHAR(10),
                    price NUMERIC(20, 8) NOT NULL,
                    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
                    value NUMERIC(20, 8),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );

                CREATE INDEX IF NOT EXISTS idx_exec_position ON execution_log(position_id);
                CREATE INDEX IF NOT EXISTS idx_exec_time ON execution_log(event_time DESC);
                """;

        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             var stmt = conn.createStatement()) {

            String[] statements = sql.split(";");
            for (String s : statements) {
                if (!s.trim().isEmpty()) {
                    stmt.execute(s.trim());
                }
            }
            logger.info("Execution log table ready");
        } catch (SQLException e) {
            logger.error("Error creating execution log table", e);
        }
    }
}

