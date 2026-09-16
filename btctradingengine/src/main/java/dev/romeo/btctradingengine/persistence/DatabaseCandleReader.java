package dev.romeo.btctradingengine.persistence;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class DatabaseCandleReader {
    private final DataSource dataSource;

    public DatabaseCandleReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<CandleEvent> loadRecentClosedCandles(String symbol, int limit) throws Exception {
        String sql = "SELECT symbol, open_time_ms, close_time_ms, open, high, low, close, volume, tick_count, "
                + "taker_buy_volume, large_buy_volume, large_sell_volume, flow_source "
                + "FROM candles WHERE symbol = ? ORDER BY close_time_ms DESC LIMIT ?";
        List<CandleEvent> candles = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setInt(2, Math.min(Math.max(limit, 1), 5000));

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    BigDecimal volume = result.getBigDecimal("volume");
                    candles.add(new CandleEvent(
                            result.getString("symbol"),
                            Instant.ofEpochMilli(result.getLong("open_time_ms")),
                            Instant.ofEpochMilli(result.getLong("close_time_ms")),
                            result.getBigDecimal("open"),
                            result.getBigDecimal("high"),
                            result.getBigDecimal("low"),
                            result.getBigDecimal("close"),
                            volume,
                            result.getInt("tick_count"),
                            readFlow(result, volume)
                    ));
                }
            }
        }

        Collections.reverse(candles);
        return candles;
    }

    /** Rows written before issue #11, or from a source without aggressor data, have no flow. */
    private static TradeFlow readFlow(ResultSet result, BigDecimal volume) throws SQLException {
        String source = result.getString("flow_source");
        BigDecimal takerBuyVolume = result.getBigDecimal("taker_buy_volume");
        if (source == null || takerBuyVolume == null) {
            return TradeFlow.none();
        }
        TradeFlow split = TradeFlow.fromKline(volume, takerBuyVolume);
        if (!TradeFlow.Source.TRADES.name().equals(source)) {
            return split;
        }
        return TradeFlow.fromTrades(
                split.takerBuyVolume(),
                split.takerSellVolume(),
                Objects.requireNonNullElse(result.getBigDecimal("large_buy_volume"), BigDecimal.ZERO),
                Objects.requireNonNullElse(result.getBigDecimal("large_sell_volume"), BigDecimal.ZERO));
    }
}
