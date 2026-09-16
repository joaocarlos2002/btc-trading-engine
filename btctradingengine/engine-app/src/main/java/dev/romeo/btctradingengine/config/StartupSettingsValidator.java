package dev.romeo.btctradingengine.config;

import dev.romeo.btctradingengine.trading.PositionSizingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The startup rules that span several property groups or need more than an annotation: the per-group
 * limits (positive periods, ranges, macd fast &lt; slow...) are Bean Validation constraints on the
 * records and fail binding with the offending key in the message.
 *
 * <p>Runs when the bean is created, so an invalid combination stops the application before the
 * pipeline or the dashboard starts.
 */
@Component("startupSettingsValidator")
public class StartupSettingsValidator {
    private static final Logger logger = LoggerFactory.getLogger(StartupSettingsValidator.class);

    public StartupSettingsValidator(MarketProperties market, TradingProperties trading, BinanceProperties binance,
                                    DerivativesProperties derivatives, OrderBookProperties orderBook, DbProperties db) {
        validate(market, trading, binance, derivatives, orderBook, db);
    }

    public static void validate(MarketProperties market, TradingProperties trading, BinanceProperties binance,
                                DerivativesProperties derivatives, OrderBookProperties orderBook, DbProperties db) {
        // Builds the strategy so a bad name or out-of-range fraction fails at startup, not at the first entry
        sizingStrategy(trading);
        if (db.password().isBlank()) {
            logger.warn("db.password is blank: set BTC_ENGINE_DB_PASSWORD in .env (see .env.example), "
                    + "the same variable docker-compose uses to create the database. "
                    + "Connecting to {} as {} will fail until then.", db.url(), db.user());
        }
        if (trading.realEnabled() && (binance.apiKey().isBlank() || binance.apiSecret().isBlank())) {
            throw new IllegalArgumentException("Real trading requires Binance API credentials");
        }
        if (trading.realEnabled() && !binance.testnetEndpoint() && !trading.confirmMainnet()) {
            throw new IllegalArgumentException(
                    "Real trading is enabled against a non-testnet Binance endpoint (" + binance.restUrl() + ") "
                    + "but trading.confirm.mainnet is not set to true. This is a safety guard against "
                    + "accidentally trading with real funds - set trading.confirm.mainnet=true only when "
                    + "you deliberately intend to go live on mainnet.");
        }
        String marketDataWarning = marketDataTestnetWarning(market.dataWsUrl(), market.dataRestUrl(),
                derivatives.enabled(), orderBook.enabled());
        if (marketDataWarning != null) {
            logger.warn(marketDataWarning);
        }
    }

    public static PositionSizingStrategy sizingStrategy(TradingProperties trading) {
        return PositionSizingStrategy.named(trading.sizingStrategy(), trading.sizingFraction(),
                trading.sizingAtrRiskPercent(), trading.sizingAtrMultiplier(),
                trading.sizingKellyFraction(), trading.sizingKellyMinTrades());
    }

    /** Null when fine; otherwise why a testnet market data feed breaks the mainnet-only features (issue #76). */
    static String marketDataTestnetWarning(String wsUrl, String restUrl, boolean derivativesEnabled,
                                           boolean orderBookEnabled) {
        boolean testnetFeed = wsUrl.toLowerCase().contains("testnet") || restUrl.toLowerCase().contains("testnet");
        if (!testnetFeed || !(derivativesEnabled || orderBookEnabled)) {
            return null;
        }
        return "Market data comes from a testnet (market.data.ws.url=" + wsUrl + ", market.data.rest.url=" + restUrl
                + ") while derivatives.enabled=" + derivativesEnabled + " and orderbook.enabled=" + orderBookEnabled
                + " read mainnet: the testnet price and volume are synthetic, so basisPercent, order book, VPIN and "
                + "CVD are not comparable with the real market or the backtest. Point market.data.* at mainnet.";
    }
}
