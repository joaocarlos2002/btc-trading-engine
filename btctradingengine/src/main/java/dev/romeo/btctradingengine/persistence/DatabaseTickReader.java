package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the ticks DatabaseWriter recorded, in arrival order, for the deterministic replay (issue #111).
 * Ticks older than {@code db.ticks.retention.days} are purged by {@link TickRetentionJob} (issue #85), so a
 * replay window reaching further back than that comes back partial or empty.
 */
public class DatabaseTickReader {
    static final String SELECT_SQL = "SELECT time_ms, price, quantity, aggressor_side FROM ticks "
            + "WHERE symbol = ? AND time_ms >= ? AND time_ms < ? ORDER BY time_ms, id";

    public List<NormalizedPriceEvent> loadTicks(String symbol, Instant from, Instant to) throws Exception {
        List<NormalizedPriceEvent> ticks = new ArrayList<>();
        try (Connection connection = DataSourceManager.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setString(1, symbol);
            statement.setLong(2, from.toEpochMilli());
            statement.setLong(3, to.toEpochMilli());
            statement.setFetchSize(10_000);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Instant time = Instant.ofEpochMilli(result.getLong("time_ms"));
                    String side = result.getString("aggressor_side");
                    ticks.add(new NormalizedPriceEvent(symbol, result.getBigDecimal("price"), time, time,
                            result.getBigDecimal("quantity"),
                            side == null ? AggressorSide.UNKNOWN : AggressorSide.valueOf(side)));
                }
            }
        }
        return ticks;
    }
}
