package dev.romeo.btctradingengine.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #101: the shipped properties bind, the profiles load, and an invalid value stops startup with its key. */
class PropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties({MarketProperties.class, IndicatorProperties.class, FeatureProperties.class,
            PredictionProperties.class, TradingProperties.class, BinanceProperties.class, DerivativesProperties.class,
            OrderBookProperties.class, BacktestProperties.class, DbProperties.class, AlertProperties.class,
            DashboardProperties.class, PriceBusProperties.class})
    @ImportAutoConfiguration(ValidationAutoConfiguration.class)
    @Import(StartupSettingsValidator.class)
    static class Settings {
    }

    private static ConfigurableApplicationContext start(String profile, String... args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Settings.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false);
        if (profile != null) {
            builder.profiles(profile);
        }
        return builder.run(args);
    }

    /** The whole cause chain, since binding failures wrap the validation message a few levels down. */
    private static String failure(String... args) {
        Exception e = assertThrows(Exception.class, () -> start(null, args).close());
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t).append('\n');
        }
        return messages.toString();
    }

    @Test
    void theShippedPropertiesBind() {
        try (var context = start(null)) {
            MarketProperties market = context.getBean(MarketProperties.class);
            assertEquals("BTCUSDT", market.symbol());
            assertEquals(60, market.interval().toSeconds());
            assertEquals("wss://stream.binance.com:9443/ws/", market.dataWsUrl());
            assertFalse(market.dataFromTestnet(), "market data is mainnet while execution is on the testnet (#76)");

            IndicatorProperties indicators = context.getBean(IndicatorProperties.class);
            assertEquals(750, indicators.smaPeriod());
            assertEquals(0, indicators.bollingerStdDev().compareTo(new BigDecimal("2.0")));

            PredictionProperties prediction = context.getBean(PredictionProperties.class);
            assertEquals(0.28, prediction.buyThreshold());
            assertEquals(2, prediction.confirmationSnapshots());

            TradingProperties trading = context.getBean(TradingProperties.class);
            assertFalse(trading.realEnabled());
            assertTrue(trading.ocoEnabled());

            BinanceProperties binance = context.getBean(BinanceProperties.class);
            assertTrue(binance.testnetEndpoint(), "execution keeps its testnet default");

            DashboardProperties dashboard = context.getBean(DashboardProperties.class);
            assertEquals(List.of("http://localhost:8080", "http://127.0.0.1:8080"), dashboard.allowedOrigins());
            assertEquals(1, dashboard.backtestMaxConcurrent());

            assertEquals(7, context.getBean(DbProperties.class).ticksRetentionDays());
            assertEquals(50000, context.getBean(PriceBusProperties.class).queueCapacity());
            assertEquals(8, context.getBean(BacktestProperties.class).klineCacheMaxEntries());
        }
    }

    @Test
    void aNonPositivePeriodFailsWithItsKey() {
        String message = failure("--indicator.rsi.period=0");
        assertTrue(message.contains("indicator.rsi.period must be positive"), message);
    }

    @Test
    void crossFieldRulesFailStartup() {
        String macd = failure("--indicator.macd.fast.period=400");
        assertTrue(macd.contains("indicator.macd.fast.period must be lower than slow.period"), macd);

        String thresholds = failure("--prediction.sell.threshold=0.1");
        assertTrue(thresholds.contains("Prediction thresholds must be within [-1, 1]"), thresholds);

        String depth = failure("--orderbook.depth.levels=42");
        assertTrue(depth.contains("orderbook.depth.levels must be one of"), depth);
    }

    @Test
    void anUnknownSizingStrategyFailsStartup() {
        String message = failure("--trading.sizing.strategy=martingale");
        assertTrue(message.contains("Unknown trading.sizing.strategy"), message);
    }

    @Test
    void realTradingNeedsCredentials() {
        String message = failure("--trading.real.enabled=true", "--binance.api.key=", "--binance.api.secret=");
        assertTrue(message.contains("Real trading requires Binance API credentials"), message);
    }

    @Test
    void theMainnetProfileStillNeedsTheExplicitConfirmation() {
        try (var context = start("mainnet")) {
            BinanceProperties binance = context.getBean(BinanceProperties.class);
            assertEquals("https://api.binance.com", binance.restUrl());
            assertFalse(binance.testnetEndpoint());
            assertFalse(context.getBean(TradingProperties.class).realEnabled(), "the profile never enables trading");
        }

        Exception e = assertThrows(Exception.class, () -> start("mainnet",
                "--trading.real.enabled=true", "--binance.api.key=k", "--binance.api.secret=s").close());
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t).append('\n');
        }
        assertTrue(messages.toString().contains("trading.confirm.mainnet is not set to true"), messages.toString());

        try (var context = start("mainnet", "--trading.real.enabled=true", "--binance.api.key=k",
                "--binance.api.secret=s", "--trading.confirm.mainnet=true")) {
            assertTrue(context.getBean(TradingProperties.class).confirmMainnet());
        }
    }

    @Test
    void theTestnetProfileLoads() {
        try (var context = start("testnet")) {
            assertEquals("https://testnet.binance.vision", context.getBean(BinanceProperties.class).restUrl());
        }
    }

    @Test
    void secretsAreMaskedInToString() {
        BinanceProperties binance = new BinanceProperties("ws", "rest", "my-key", "my-secret", 1, 1, 1, "f", "d");
        DbProperties db = new DbProperties("jdbc:x", "u", "pw-123", 1, 1, 1, 7, 1, 1);
        AlertProperties alert = new AlertProperties("https://discord.com/api/webhooks/1/token");
        assertFalse(binance.toString().contains("my-key") || binance.toString().contains("my-secret"));
        assertFalse(db.toString().contains("pw-123"));
        assertFalse(alert.toString().contains("token"));
    }

    @Test
    void environmentBeatsDotEnvAndBlankValuesFallThrough() {
        Map<String, String> environment = Map.of(
                "BTC_ENGINE_DB_PASSWORD", "from-env",
                "BTC_ENGINE_TRADING_OCO_ENABLED", " ");
        Map<String, String> dotEnv = Map.of(
                "BTC_ENGINE_DB_PASSWORD", "from-dotenv",
                "BTC_ENGINE_BINANCE_API_KEY", "key-from-dotenv",
                "BTC_ENGINE_TRADING_SIZING_STRATEGY", "");

        Map<String, Object> resolved = SecretsEnvironmentPostProcessor.resolve(environment::get, dotEnv);

        assertEquals("from-env", resolved.get("db.password"));
        assertEquals("key-from-dotenv", resolved.get("binance.api.key"));
        assertFalse(resolved.containsKey("trading.oco.enabled"), "a blank variable keeps the property value");
        assertFalse(resolved.containsKey("trading.sizing.strategy"), "a blank .env entry keeps the property value");
    }

    @Test
    void theVariablesReachTheBoundRecords() {
        // The post-processor is registered in spring.factories and maps the variable onto trading.oco.enabled,
        // over the application.properties value (true). A command-line arg stands in for the environment here.
        try (var context = start(null, "--BTC_ENGINE_TRADING_OCO_ENABLED=false")) {
            assertFalse(context.getBean(TradingProperties.class).ocoEnabled());
        }
    }
}
