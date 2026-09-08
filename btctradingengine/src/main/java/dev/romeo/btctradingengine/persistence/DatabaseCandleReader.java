package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatabaseCandleReader {
    public List<CandleEvent> loadRecentClosedCandles(String symbol, int limit) throws Exception {
        String sql = "SELECT symbol, open_time_ms, close_time_ms, open, high, low, close, volume, tick_count "
                + "FROM candles WHERE symbol = ? ORDER BY close_time_ms DESC LIMIT ?";
        List<CandleEvent> candles = new ArrayList<>();

        try (Connection connection = DataSourceManager.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setInt(2, Math.min(Math.max(limit, 1), 5000));

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    candles.add(new CandleEvent(
                            result.getString("symbol"),
                            Instant.ofEpochMilli(result.getLong("open_time_ms")),
                            Instant.ofEpochMilli(result.getLong("close_time_ms")),
                            result.getBigDecimal("open"),
                            result.getBigDecimal("high"),
                            result.getBigDecimal("low"),
                            result.getBigDecimal("close"),
                            result.getBigDecimal("volume"),
                            result.getInt("tick_count")
                    ));
                }
            }
        }

        Collections.reverse(candles);
        return candles;
    }
}

