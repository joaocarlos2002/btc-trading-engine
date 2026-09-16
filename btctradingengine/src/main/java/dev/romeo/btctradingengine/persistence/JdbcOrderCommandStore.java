package dev.romeo.btctradingengine.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.trading.OrderCommand;
import dev.romeo.btctradingengine.trading.OrderCommandStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Order outbox in the order_commands table (issue #111). */
public class JdbcOrderCommandStore implements OrderCommandStore {
    private static final Logger logger = LoggerFactory.getLogger(JdbcOrderCommandStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS order_commands (
                id BIGSERIAL PRIMARY KEY,
                client_order_id VARCHAR(64) NOT NULL UNIQUE,
                position_id VARCHAR(50) NOT NULL,
                type VARCHAR(10) NOT NULL,
                payload TEXT NOT NULL DEFAULT '{}',
                status VARCHAR(10) NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 1,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_error TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_order_commands_unresolved ON order_commands(status)
                WHERE status IN ('PENDING', 'SENT');
            CREATE INDEX IF NOT EXISTS idx_order_commands_position ON order_commands(position_id)
            """;

    static final String RECORD_SQL = """
            INSERT INTO order_commands (client_order_id, position_id, type, payload, status)
            VALUES (?, ?, ?, ?, 'PENDING')
            ON CONFLICT (client_order_id) DO UPDATE SET
                position_id = EXCLUDED.position_id,
                type = EXCLUDED.type,
                payload = EXCLUDED.payload,
                status = 'PENDING',
                attempts = order_commands.attempts + 1,
                updated_at = CURRENT_TIMESTAMP,
                last_error = NULL
            """;

    static final String UPDATE_SQL =
            "UPDATE order_commands SET status = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP WHERE client_order_id = ?";

    private static final String SELECT_COLUMNS =
            "SELECT id, client_order_id, position_id, type, payload, status, attempts, created_at, updated_at, last_error "
                    + "FROM order_commands ";

    private final Supplier<DataSource> dataSource;

    public JdbcOrderCommandStore() {
        this(DataSourceManager::getDataSource);
    }

    public JdbcOrderCommandStore(Supplier<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    public void createTableIfNotExists() {
        try (Connection conn = dataSource.get().getConnection(); var stmt = conn.createStatement()) {
            for (String sql : CREATE_SQL.split(";")) {
                if (!sql.isBlank()) {
                    stmt.execute(sql.trim());
                }
            }
            logger.info("Order command outbox table ready");
        } catch (SQLException e) {
            logger.error("Error creating order_commands table", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void record(String clientOrderId, String positionId, OrderCommand.Type type, Map<String, String> payload) {
        try (Connection conn = dataSource.get().getConnection();
             PreparedStatement stmt = conn.prepareStatement(RECORD_SQL)) {
            stmt.setString(1, clientOrderId);
            stmt.setString(2, positionId);
            stmt.setString(3, type.name());
            stmt.setString(4, MAPPER.writeValueAsString(payload == null ? Map.of() : payload));
            stmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Could not record order command {} in the outbox", clientOrderId, e);
        }
    }

    @Override
    public void markSent(String clientOrderId) {
        update(clientOrderId, OrderCommand.Status.SENT, null);
    }

    @Override
    public void markConfirmed(String clientOrderId) {
        update(clientOrderId, OrderCommand.Status.CONFIRMED, null);
    }

    @Override
    public void markFailed(String clientOrderId, String error) {
        update(clientOrderId, OrderCommand.Status.FAILED, error);
    }

    private void update(String clientOrderId, OrderCommand.Status status, String error) {
        try (Connection conn = dataSource.get().getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
            stmt.setString(1, status.name());
            stmt.setString(2, error);
            stmt.setString(3, clientOrderId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Could not mark order command {} as {}", clientOrderId, status, e);
        }
    }

    @Override
    public Optional<OrderCommand> find(String clientOrderId) {
        List<OrderCommand> found = query(SELECT_COLUMNS + "WHERE client_order_id = ?", clientOrderId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    @Override
    public List<OrderCommand> findUnresolved() {
        return query(SELECT_COLUMNS + "WHERE status IN ('PENDING', 'SENT') ORDER BY id", null);
    }

    private List<OrderCommand> query(String sql, String parameter) {
        List<OrderCommand> commands = new ArrayList<>();
        try (Connection conn = dataSource.get().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (parameter != null) {
                stmt.setString(1, parameter);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    commands.add(map(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Could not read the order outbox", e);
        }
        return commands;
    }

    private static OrderCommand map(ResultSet rs) throws Exception {
        Map<String, String> payload = MAPPER.readValue(rs.getString("payload"), new TypeReference<>() { });
        return new OrderCommand(
                rs.getLong("id"),
                rs.getString("client_order_id"),
                rs.getString("position_id"),
                OrderCommand.Type.valueOf(rs.getString("type")),
                payload,
                OrderCommand.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("last_error"));
    }
}
