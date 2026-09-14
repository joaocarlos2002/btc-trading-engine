package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.orderbook.BinanceDepthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Persists each order book poll (issue #10). Binance offers no free order book history, so these rows
 * are the only way order book features can ever be backtested.
 */
public class OrderBookSnapshotWriter {
    private static final Logger logger = LoggerFactory.getLogger(OrderBookSnapshotWriter.class);

    private static final String INSERT_SQL =
            "INSERT INTO order_book_snapshots (symbol, time_ms, levels, bid_volume, ask_volume) VALUES (?, ?, ?, ?, ?)";

    public void write(String symbol, Instant takenAt, BinanceDepthClient.DepthSnapshot snapshot) {
        try (Connection conn = DataSourceManager.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            stmt.setString(1, symbol);
            stmt.setLong(2, takenAt.toEpochMilli());
            stmt.setInt(3, snapshot.levels());
            stmt.setBigDecimal(4, snapshot.bidVolume());
            stmt.setBigDecimal(5, snapshot.askVolume());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error persisting order book snapshot: {}", takenAt, e);
        }
    }
}
