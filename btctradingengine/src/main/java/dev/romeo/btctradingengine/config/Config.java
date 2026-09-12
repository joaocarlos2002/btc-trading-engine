package dev.romeo.btctradingengine.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Properties;

public class Config {

    private static final Properties props = new Properties();

    static {
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            System.err.println("Could not load application.properties: " + e.getMessage());
        }
    }

    public static String getMarketSymbol() {
        return getProperty("market.symbol", "BTCUSDT");
    }

    public static Duration getMarketInterval() {
        long seconds = Long.parseLong(getProperty("market.interval.seconds", "5"));
        return Duration.ofSeconds(seconds);
    }

    public static String getBinanceKlineInterval() {
        return getProperty("market.binance.interval", "1m");
    }

    public static int getHistoryCandles() {
        return Integer.parseInt(getProperty("market.history.candles", "1000"));
    }

    public static int getSmaPeriod() {
        return Integer.parseInt(getProperty("indicator.sma.period", "750"));
    }

    public static int getEmaPeriod() {
        return Integer.parseInt(getProperty("indicator.ema.period", "390"));
    }

    public static int getRsiPeriod() {
        return Integer.parseInt(getProperty("indicator.rsi.period", "210"));
    }

    public static int getAtrPeriod() { return Integer.parseInt(getProperty("indicator.atr.period", "210")); }
    public static int getMacdFastPeriod() { return Integer.parseInt(getProperty("indicator.macd.fast.period", "180")); }
    public static int getMacdSlowPeriod() { return Integer.parseInt(getProperty("indicator.macd.slow.period", "390")); }
    public static int getMacdSignalPeriod() { return Integer.parseInt(getProperty("indicator.macd.signal.period", "135")); }
    public static int getVolatilityShortPeriods() { return Integer.parseInt(getProperty("feature.volatility.short.periods", "75")); }
    public static int getVolatilityLongPeriods() { return Integer.parseInt(getProperty("feature.volatility.long.periods", "300")); }
    public static int getVolumeAveragePeriods() { return Integer.parseInt(getProperty("feature.volume.average.periods", "300")); }

    public static BigDecimal getDecimal(String key, String defaultValue) {
        return new BigDecimal(getProperty(key, defaultValue));
    }

    public static BigDecimal getRsiOversold() { return getDecimal("prediction.rsi.oversold", "30"); }
    public static BigDecimal getRsiNeutralLow() { return getDecimal("prediction.rsi.neutral.low", "40"); }
    public static BigDecimal getRsiNeutralHigh() { return getDecimal("prediction.rsi.neutral.high", "60"); }
    public static BigDecimal getRsiOverbought() { return getDecimal("prediction.rsi.overbought", "70"); }
    public static BigDecimal getSmaDistanceExtreme() { return getDecimal("prediction.sma.distance.extreme", "3"); }
    public static BigDecimal getSmaDistanceModerate() { return getDecimal("prediction.sma.distance.moderate", "1"); }
    public static BigDecimal getAtrVolatilityLow() { return getDecimal("prediction.atr.volatility.low", "0.023"); }
    public static BigDecimal getAtrVolatilityNormal() { return getDecimal("prediction.atr.volatility.normal", "0.060"); }
    public static BigDecimal getAtrVolatilityHigh() { return getDecimal("prediction.atr.volatility.high", "0.119"); }
    public static BigDecimal getMacdStrongHistogramAtrRatio() {
        return getDecimal("prediction.macd.histogram.strong.atr.ratio", "0.20");
    }
    public static BigDecimal getVolatilityRatioHigh() { return getDecimal("prediction.volatility.ratio.high", "1.5"); }
    public static double getBuyThreshold() { return Double.parseDouble(getProperty("prediction.buy.threshold", "0.28")); }
    public static double getSellThreshold() { return Double.parseDouble(getProperty("prediction.sell.threshold", "-0.28")); }
    public static double getHoldMin() { return Double.parseDouble(getProperty("prediction.hold.min", "-0.3")); }
    public static double getHoldMax() { return Double.parseDouble(getProperty("prediction.hold.max", "0.3")); }
    public static int getConfirmationSnapshots() { return Integer.parseInt(getProperty("prediction.confirmation.snapshots", "2")); }
    public static BigDecimal getTradingTargetPercent() { return getDecimal("trading.target.percent", "2.0"); }
    public static BigDecimal getTradingStopLossPercent() { return getDecimal("trading.stop.loss.percent", "1.5"); }
    public static boolean isRealTradingEnabled() { return Boolean.parseBoolean(getProperty("trading.real.enabled", "false")); }
    public static boolean isBinanceTestnetEndpoint() { return getBinanceRestUrl().toLowerCase().contains("testnet"); }
    public static boolean isMainnetTradingConfirmed() { return Boolean.parseBoolean(getProperty("trading.confirm.mainnet", "false")); }
    public static BigDecimal getTradingInitialCapital() { return getDecimal("trading.initial.capital.usdt", "10"); }
    public static BigDecimal getTradingMaxDrawdownPercent() { return getDecimal("trading.max.drawdown.percent", "5"); }
    public static long getMaxDataStalenessSeconds() { return Long.parseLong(getProperty("trading.max.data.staleness.seconds", "60")); }
    public static BigDecimal getBacktestCommissionRate() { return getDecimal("backtest.commission.rate", "0.001"); }
    public static String getAlertDiscordWebhookUrl() {
        return getEnvironmentOrProperty("btc-trading-engine_ALERT_DISCORD_WEBHOOK_URL", "alert.discord.webhook.url", "");
    }

    public static void validate() {
        if (getMarketSymbol().isBlank()) {
            throw new IllegalArgumentException("market.symbol cannot be blank");
        }
        requirePositive("market.interval.seconds", getMarketInterval().getSeconds());
        requirePositive("market.history.candles", getHistoryCandles());
        requirePositive("indicator.sma.period", getSmaPeriod());
        requirePositive("indicator.ema.period", getEmaPeriod());
        requirePositive("indicator.rsi.period", getRsiPeriod());
        requirePositive("indicator.atr.period", getAtrPeriod());
        requirePositive("indicator.macd.fast.period", getMacdFastPeriod());
        requirePositive("indicator.macd.slow.period", getMacdSlowPeriod());
        requirePositive("indicator.macd.signal.period", getMacdSignalPeriod());
        requirePositive("feature.volatility.short.periods", getVolatilityShortPeriods());
        requirePositive("feature.volatility.long.periods", getVolatilityLongPeriods());
        requirePositive("feature.volume.average.periods", getVolumeAveragePeriods());

        if (getMacdFastPeriod() >= getMacdSlowPeriod()) {
            throw new IllegalArgumentException("indicator.macd.fast.period must be lower than slow.period");
        }
        if (getHoldMin() >= getHoldMax()) {
            throw new IllegalArgumentException("prediction.hold.min must be lower than hold.max");
        }
        if (getBuyThreshold() <= 0 || getBuyThreshold() > 1
                || getSellThreshold() >= 0 || getSellThreshold() < -1
                || getHoldMin() < -1 || getHoldMax() > 1) {
            throw new IllegalArgumentException("Prediction thresholds must be within [-1, 1] with buy positive and sell negative");
        }
        if (getTradingTargetPercent().compareTo(BigDecimal.ZERO) <= 0
                || getTradingStopLossPercent().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Trading target and stop loss must be positive");
        }
        if (getTradingInitialCapital().compareTo(BigDecimal.ZERO) <= 0
                || getTradingMaxDrawdownPercent().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Trading capital and max drawdown must be positive");
        }
        requirePositive("trading.max.data.staleness.seconds", getMaxDataStalenessSeconds());
        if (isRealTradingEnabled()
                && (getBinanceApiKey().isBlank() || getBinanceApiSecret().isBlank())) {
            throw new IllegalArgumentException("Real trading requires Binance API credentials");
        }
        if (isRealTradingEnabled() && !isBinanceTestnetEndpoint() && !isMainnetTradingConfirmed()) {
            throw new IllegalArgumentException(
                    "Real trading is enabled against a non-testnet Binance endpoint (" + getBinanceRestUrl() + ") "
                    + "but trading.confirm.mainnet is not set to true. This is a safety guard against "
                    + "accidentally trading with real funds - set trading.confirm.mainnet=true only when "
                    + "you deliberately intend to go live on mainnet.");
        }
        if (getBacktestCommissionRate().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("backtest.commission.rate cannot be negative");
        }
    }

    private static void requirePositive(String key, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
    }

    public static String getBinanceWsUrl() {
        return getProperty("binance.ws.url", "wss://stream.binance.com:9443/ws/");
    }

    public static String getBinanceRestUrl() {
        return getProperty("binance.rest.url", "https://api.binance.com");
    }

    public static int getBinanceMaxRetries() {
        return Integer.parseInt(getProperty("binance.max.retries", "10"));
    }

    public static long getBinanceInitialBackoffMs() {
        return Long.parseLong(getProperty("binance.initial.backoff.ms", "1000"));
    }

    public static long getBinanceMaxBackoffMs() {
        return Long.parseLong(getProperty("binance.max.backoff.ms", "60000"));
    }

    public static String getDbUrl() {
        return getProperty("db.url", "jdbc:postgresql://localhost:5432/btc-trading-engine_btc");
    }

    public static String getDbUser() {
        return getProperty("db.user", "btc-trading-engine");
    }

    public static String getDbPassword() {
        return getEnvironmentOrProperty("btc-trading-engine_DB_PASSWORD", "db.password", "");
    }

    public static String getBinanceApiKey() {
        return getEnvironmentOrProperty("btc-trading-engine_BINANCE_API_KEY", "binance.api.key", "");
    }

    public static String getBinanceApiSecret() {
        return getEnvironmentOrProperty("btc-trading-engine_BINANCE_API_SECRET", "binance.api.secret", "");
    }

    public static int getDbPoolSize() {
        return Integer.parseInt(getProperty("db.pool.size", "10"));
    }

    public static long getDbIdleTimeoutMs() {
        return Long.parseLong(getProperty("db.pool.idle.timeout.ms", "300000"));
    }

    public static long getDbMaxLifetimeMs() {
        return Long.parseLong(getProperty("db.pool.max.lifetime.ms", "1800000"));
    }

    private static String getProperty(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static String getEnvironmentOrProperty(String environmentKey, String propertyKey, String defaultValue) {
        String environmentValue = System.getenv(environmentKey);
        return environmentValue == null || environmentValue.isBlank()
                ? getProperty(propertyKey, defaultValue)
                : environmentValue;
    }
}

