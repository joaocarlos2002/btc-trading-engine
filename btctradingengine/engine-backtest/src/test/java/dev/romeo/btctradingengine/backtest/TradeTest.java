package dev.romeo.btctradingengine.backtest;

import dev.romeo.btctradingengine.prediction.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeTest {

    @Test
    void describesAnOpenTradeWithoutThrowing() {
        Trade trade = new Trade("TRADE_1", Signal.BUY, new BigDecimal("100"), Instant.EPOCH);

        assertEquals("TRADE_1: BUY @ 100.00 -> OPEN | P&L: N/A (N/A%)", trade.toString());
    }

    @Test
    void describesAClosedTrade() {
        Trade trade = new Trade("TRADE_2", Signal.BUY, new BigDecimal("100"), Instant.EPOCH);
        trade.close(new BigDecimal("102.5"), Instant.EPOCH.plusSeconds(60));

        assertEquals("TRADE_2: BUY @ 100.00 -> 102.50 | P&L: 2.50 (2.50%)", trade.toString());
    }
}
