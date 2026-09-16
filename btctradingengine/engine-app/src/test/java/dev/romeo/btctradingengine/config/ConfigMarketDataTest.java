package dev.romeo.btctradingengine.config;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

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
    void marketDataIsMainnetWhileExecutionStaysOnTheTestnet() throws Exception {
        Properties shipped = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            shipped.load(in);
        }
        assertEquals("wss://stream.binance.com:9443/ws/", shipped.getProperty("market.data.ws.url"));
        assertEquals("https://api.binance.com", shipped.getProperty("market.data.rest.url"));

        assertTrue(shipped.getProperty("binance.rest.url").contains("testnet"), "execution keeps its testnet default");
        assertTrue(shipped.getProperty("binance.ws.url").contains("testnet"));
    }

    @Test
    void warnsOnlyForATestnetFeedWithMainnetFeaturesOn() {
        String testnetWs = "wss://stream.testnet.binance.vision:9443/ws/";
        String testnetRest = "https://testnet.binance.vision";
        String mainnetWs = "wss://stream.binance.com:9443/ws/";
        String mainnetRest = "https://api.binance.com";

        String warning = StartupSettingsValidator.marketDataTestnetWarning(testnetWs, testnetRest, true, false);
        assertNotNull(warning);
        assertTrue(warning.contains("market.data.ws.url"), warning);
        assertNotNull(StartupSettingsValidator.marketDataTestnetWarning(mainnetWs, testnetRest, false, true), "either URL counts");
        assertNotNull(StartupSettingsValidator.marketDataTestnetWarning(testnetWs, mainnetRest, true, true));

        assertNull(StartupSettingsValidator.marketDataTestnetWarning(testnetWs, testnetRest, false, false),
                "a testnet feed alone is consistent when nothing reads mainnet");
        assertNull(StartupSettingsValidator.marketDataTestnetWarning(mainnetWs, mainnetRest, true, true));
    }


    @Test
    void validateAcceptsTheShippedConfiguration() {
        assertDoesNotThrow(() -> new SpringApplicationBuilder(PropertiesBindingTest.Settings.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run().close());
    }
}
