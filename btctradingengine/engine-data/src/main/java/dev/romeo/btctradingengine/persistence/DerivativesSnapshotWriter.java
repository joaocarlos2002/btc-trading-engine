package dev.romeo.btctradingengine.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Persists each derivatives poll, so open interest and long/short accumulate a history the futures
 * REST API does not keep (it only serves 30 days). One row per poll, written from the poller thread.
 */
public class DerivativesSnapshotWriter {
    private static final Logger logger = LoggerFactory.getLogger(DerivativesSnapshotWriter.class);

    private final DataSource dataSource;

    public DerivativesSnapshotWriter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final String INSERT_SQL =
            "INSERT INTO derivatives_snapshots (symbol, time_ms, open_interest, long_short_ratio, funding_rate) " +
            "VALUES (?, ?, ?, ?, ?)";

    /** Null values are stored as NULL: that reading failed in this poll, it was not zero. */
    public void write(String symbol, Instant observedAt, BigDecimal openInterest,
                      BigDecimal longShortRatio, BigDecimal fundingRate) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {

            stmt.setString(1, symbol);
            stmt.setLong(2, observedAt.toEpochMilli());
            stmt.setBigDecimal(3, openInterest);
            stmt.setBigDecimal(4, longShortRatio);
            stmt.setBigDecimal(5, fundingRate);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error persisting derivatives snapshot: {}", observedAt, e);
        }
    }
}
