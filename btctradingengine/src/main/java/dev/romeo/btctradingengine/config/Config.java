package dev.romeo.btctradingengine.config;

import dev.romeo.btctradingengine.indicator.VwapAnchor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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

    // Periods below are scaled x15 for 1m candles, like the ones above (e.g. ADX 14*15m -> 210*1m).
    public static int getAdxPeriod() { return Integer.parseInt(getProperty("indicator.adx.period", "210")); }
    public static int getBollingerPeriod() { return Integer.parseInt(getProperty("indicator.bollinger.period", "300")); }
    public static BigDecimal getBollingerStdDev() { return getDecimal("indicator.bollinger.stddev", "2.0"); }
    public static int getMfiPeriod() { return Integer.parseInt(getProperty("indicator.mfi.period", "210")); }
    public static int getDonchianPeriod() { return Integer.parseInt(getProperty("indicator.donchian.period", "300")); }
    public static VwapAnchor getVwapAnchor() { return VwapAnchor.fromProperty(getProperty("indicator.vwap.anchor", "daily")); }
    public static int getVwapRollingPeriods() { return Integer.parseInt(getProperty("indicator.vwap.rolling.periods", "300")); }
    public static int getPriceActionLookback() { return Integer.parseInt(getProperty("indicator.priceaction.lookback", "300")); }
    public static int getPriceActionSwingStrength() { return Integer.parseInt(getProperty("indicator.priceaction.swing.strength", "30")); }
    public static int getCvdPeriod() { return Integer.parseInt(getProperty("indicator.cvd.period", "300")); }
    public static int getEmaSlopePeriods() { return Integer.parseInt(getProperty("indicator.ema.slope.periods", "15")); }
    public static int getVpinBuckets() { return Integer.parseInt(getProperty("indicator.vpin.buckets", "50")); }
    public static int getVpinBucketCandles() { return Integer.parseInt(getProperty("indicator.vpin.bucket.candles", "20")); }
    public static BigDecimal getAbsorptionDeltaMin() { return getDecimal("feature.absorption.delta.min", "0.3"); }
    public static BigDecimal getAbsorptionVolumeRatioMin() { return getDecimal("feature.absorption.volume.ratio.min", "1.5"); }
    public static BigDecimal getAbsorptionMaxMoveAtr() { return getDecimal("feature.absorption.max.move.atr", "0.25"); }
    public static int getAbsorptionWindow() { return Integer.parseInt(getProperty("feature.absorption.window", "15")); }
    public static BigDecimal getLargeTradeNotional() { return getDecimal("feature.flow.large.trade.notional", "100000"); }

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
    public static BigDecimal getAdxTrendMin() { return getDecimal("prediction.adx.trend.min", "20"); }
    public static BigDecimal getAdxTrendStrong() { return getDecimal("prediction.adx.trend.strong", "25"); }
    public static BigDecimal getMfiOversold() { return getDecimal("prediction.mfi.oversold", "20"); }
    public static BigDecimal getMfiNeutralLow() { return getDecimal("prediction.mfi.neutral.low", "40"); }
    public static BigDecimal getMfiNeutralHigh() { return getDecimal("prediction.mfi.neutral.high", "60"); }
    public static BigDecimal getMfiOverbought() { return getDecimal("prediction.mfi.overbought", "80"); }
    public static BigDecimal getBollingerSqueezeThreshold() { return getDecimal("prediction.bollinger.squeeze.threshold", "0.5"); }
    public static boolean isRegimeGatingEnabled() { return Boolean.parseBoolean(getProperty("prediction.regime.gating.enabled", "false")); }
    public static boolean isVpinFilterEnabled() { return Boolean.parseBoolean(getProperty("prediction.vpin.filter.enabled", "false")); }
    public static BigDecimal getVpinHighThreshold() { return getDecimal("prediction.vpin.high", "0.35"); }
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
    public static boolean isShortSellingAllowed() { return Boolean.parseBoolean(getProperty("trading.allow.short", "false")); }
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
        requirePositive("indicator.adx.period", getAdxPeriod());
        requirePositive("indicator.bollinger.period", getBollingerPeriod());
        requirePositive("indicator.mfi.period", getMfiPeriod());
        requirePositive("indicator.donchian.period", getDonchianPeriod());
        requirePositive("indicator.vwap.rolling.periods", getVwapRollingPeriods());
        requirePositive("indicator.priceaction.lookback", getPriceActionLookback());
        requirePositive("indicator.priceaction.swing.strength", getPriceActionSwingStrength());
        requirePositive("indicator.cvd.period", getCvdPeriod());
        requirePositive("indicator.ema.slope.periods", getEmaSlopePeriods());
        requirePositive("indicator.vpin.buckets", getVpinBuckets());
        requirePositive("indicator.vpin.bucket.candles", getVpinBucketCandles());
        requirePositive("feature.absorption.window", getAbsorptionWindow());
        if (getAbsorptionDeltaMin().compareTo(BigDecimal.ZERO) <= 0 || getAbsorptionDeltaMin().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("feature.absorption.delta.min must be in (0, 1]");
        }
        if (getAbsorptionVolumeRatioMin().compareTo(BigDecimal.ZERO) <= 0 || getAbsorptionMaxMoveAtr().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("feature.absorption.volume.ratio.min must be positive and max.move.atr non-negative");
        }
        if (getVpinHighThreshold().compareTo(BigDecimal.ZERO) <= 0 || getVpinHighThreshold().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("prediction.vpin.high must be in (0, 1]");
        }
        requirePositive("derivatives.poll.seconds", getDerivativesPollSeconds());
        requirePositive("derivatives.basis.poll.seconds", getDerivativesBasisPollSeconds());
        requirePositive("derivatives.stale.seconds", getDerivativesStaleSeconds());
        requirePositive("derivatives.open.interest.change.minutes", getOpenInterestChangeMinutes());
        requirePositive("orderbook.poll.seconds", getOrderBookPollSeconds());
        if (!java.util.Set.of(5, 10, 20, 50, 100, 500, 1000, 5000).contains(getOrderBookDepthLevels())) {
            throw new IllegalArgumentException("orderbook.depth.levels must be one of 5, 10, 20, 50, 100, 500, 1000, 5000");
        }
        if (getOrderBookBuyMin().compareTo(getOrderBookSellMax()) >= 0) {
            throw new IllegalArgumentException("prediction.orderbook.buy.min must be lower than prediction.orderbook.sell.max");
        }
        if (getLargeTradeNotional().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("feature.flow.large.trade.notional must be positive");
        }

        if (getMacdFastPeriod() >= getMacdSlowPeriod()) {
            throw new IllegalArgumentException("indicator.macd.fast.period must be lower than slow.period");
        }
        if (getBollingerStdDev().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("indicator.bollinger.stddev must be positive");
        }
        if (getAdxTrendMin().compareTo(getAdxTrendStrong()) > 0) {
            throw new IllegalArgumentException("prediction.adx.trend.min cannot be above prediction.adx.trend.strong");
        }
        if (getMfiOversold().compareTo(getMfiOverbought()) >= 0) {
            throw new IllegalArgumentException("prediction.mfi.oversold must be lower than prediction.mfi.overbought");
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

    // Derivatives (issue #53) always come from mainnet USD-M futures: testnet futures data is synthetic.
    public static boolean isDerivativesEnabled() { return Boolean.parseBoolean(getProperty("derivatives.enabled", "true")); }
    public static String getBinanceFuturesRestUrl() { return getProperty("binance.futures.rest.url", "https://fapi.binance.com"); }
    public static long getDerivativesPollSeconds() { return Long.parseLong(getProperty("derivatives.poll.seconds", "60")); }
    public static long getDerivativesBasisPollSeconds() { return Long.parseLong(getProperty("derivatives.basis.poll.seconds", "5")); }
    public static long getDerivativesStaleSeconds() { return Long.parseLong(getProperty("derivatives.stale.seconds", "300")); }
    public static long getOpenInterestChangeMinutes() { return Long.parseLong(getProperty("derivatives.open.interest.change.minutes", "60")); }
    public static String getBinanceDataUrl() { return getProperty("binance.data.url", "https://data.binance.vision"); }

    /** Blank means a folder under java.io.tmpdir, so a fresh checkout needs no setup. */
    public static java.nio.file.Path getMetricsCacheDir() {
        String dir = getProperty("derivatives.metrics.cache.dir", "");
        return dir.isBlank()
                ? java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "btc-trading-engine", "metrics")
                : java.nio.file.Path.of(dir);
    }

    /** Blank means a folder under java.io.tmpdir, like the metrics cache. */
    public static java.nio.file.Path getAggTradesCacheDir() {
        String dir = getProperty("backtest.aggtrades.cache.dir", "");
        return dir.isBlank()
                ? java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "btc-trading-engine", "aggtrades")
                : java.nio.file.Path.of(dir);
    }

    // Order book (issue #10): mainnet by default, the testnet book is synthetic.
    public static boolean isOrderBookEnabled() { return Boolean.parseBoolean(getProperty("orderbook.enabled", "true")); }
    public static String getOrderBookRestUrl() { return getProperty("orderbook.rest.url", "https://api.binance.com"); }
    public static long getOrderBookPollSeconds() { return Long.parseLong(getProperty("orderbook.poll.seconds", "5")); }
    public static int getOrderBookDepthLevels() { return Integer.parseInt(getProperty("orderbook.depth.levels", "100")); }
    public static boolean isOrderBookFilterEnabled() { return Boolean.parseBoolean(getProperty("prediction.orderbook.filter.enabled", "false")); }
    public static BigDecimal getOrderBookBuyMin() { return getDecimal("prediction.orderbook.buy.min", "0.35"); }
    public static BigDecimal getOrderBookSellMax() { return getDecimal("prediction.orderbook.sell.max", "0.65"); }

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

    /** Origins allowed to open the /ws/live WebSocket. */
    public static List<String> getDashboardAllowedOrigins() {
        return Arrays.stream(getProperty("dashboard.allowed.origins",
                        "http://localhost:8080,http://127.0.0.1:8080").split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
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

