package dev.romeo.btctradingengine.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #76: market data (ticks, warmup) must come from mainnet even when execution is on the
 * testnet, so live prices match the mainnet derivatives, order book and backtest.
 */
class ConfigMarketDataTest {

    @Test
    void marketDataIsMainnetWhileExecutionStaysOnTheTestnet() {
        assertEquals("wss://stream.binance.com:9443/ws/", Config.getMarketDataWsUrl());
        assertEquals("https://api.binance.com", Config.getMarketDataRestUrl());
        assertFalse(Config.isMarketDataTestnetEndpoint());

        assertTrue(Config.getBinanceRestUrl().contains("testnet"), "execution keeps its testnet default");
        assertTrue(Config.getBinanceWsUrl().contains("testnet"));
    }

    @Test
    void warnsOnlyForATestnetFeedWithMainnetFeaturesOn() {
        String testnetWs = "wss://stream.testnet.binance.vision:9443/ws/";
        String testnetRest = "https://testnet.binance.vision";
        String mainnetWs = "wss://stream.binance.com:9443/ws/";
        String mainnetRest = "https://api.binance.com";

        String warning = Config.marketDataTestnetWarning(testnetWs, testnetRest, true, false);
        assertNotNull(warning);
        assertTrue(warning.contains("market.data.ws.url"), warning);
        assertNotNull(Config.marketDataTestnetWarning(mainnetWs, testnetRest, false, true), "either URL counts");
        assertNotNull(Config.marketDataTestnetWarning(testnetWs, mainnetRest, true, true));

        assertNull(Config.marketDataTestnetWarning(testnetWs, testnetRest, false, false),
                "a testnet feed alone is consistent when nothing reads mainnet");
        assertNull(Config.marketDataTestnetWarning(mainnetWs, mainnetRest, true, true));
    }

    @Test
    void validateAcceptsTheShippedConfiguration() {
        assertDoesNotThrow(Config::validate);
    }
}
