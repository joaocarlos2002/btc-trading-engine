package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #76: the live feed and warmup read the market data URLs, never the execution ones. */
class MarketDataUrlTest {

    @Test
    void aggTradeStreamUsesTheMarketDataWsUrl() {
        String expected = Config.getMarketDataWsUrl() + Config.getMarketSymbol().toLowerCase() + "@aggTrade";
        assertEquals(expected, new BinanceAdapter().streamUrl());
        assertEquals("wss://example/ws/btcusdt@aggTrade", new BinanceAdapter("wss://example/ws/").streamUrl());
    }

    @Test
    void warmupKlinesUseTheMarketDataRestUrl() {
        assertEquals(Config.getMarketDataRestUrl(), new BinanceKlineClient().liveBaseUrl());
        assertEquals(Config.getBinanceFuturesRestUrl(), BinanceKlineClient.usdmFutures().liveBaseUrl(),
                "perpetual klines keep their own mainnet futures URL");
    }
}
