package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.resilience.BinanceResilience;
import dev.romeo.btctradingengine.resilience.BinanceResilienceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #76: the live feed and warmup read the market data URLs they are given, never the execution ones. */
class MarketDataUrlTest {

    @Test
    void aggTradeStreamUsesTheMarketDataWsUrl() {
        assertEquals("wss://stream.binance.com:9443/ws/btcusdt@aggTrade",
                new BinanceAdapter("wss://stream.binance.com:9443/ws/", "BTCUSDT", 1, 1, 1).streamUrl());
        assertEquals("wss://example/ws/btcusdt@aggTrade", new BinanceAdapter("wss://example/ws/", "BTCUSDT", 1, 1, 1).streamUrl());
    }

    @Test
    void warmupKlinesUseTheMarketDataRestUrl() {
        assertEquals("https://api.binance.com", new BinanceKlineClient(new BinanceResilience(BinanceResilienceSettings.defaults()), "https://api.binance.com", 8, 600_000).liveBaseUrl());
        assertEquals("https://fapi.binance.com",
                BinanceKlineClient.usdmFutures(new BinanceResilience(BinanceResilienceSettings.defaults()), "https://fapi.binance.com", 8, 600_000).liveBaseUrl(),
                "perpetual klines keep their own mainnet futures URL");
    }
}
