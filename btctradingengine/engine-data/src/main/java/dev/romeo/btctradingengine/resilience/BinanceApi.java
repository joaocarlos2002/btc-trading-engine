package dev.romeo.btctradingengine.resilience;

/** Which Binance weight budget a request counts against (issue #100). */
public enum BinanceApi {
    /** api.binance.com / testnet.binance.vision: REQUEST_WEIGHT per IP per minute. */
    SPOT,
    /** fapi.binance.com: its own REQUEST_WEIGHT per IP per minute. */
    FUTURES,
    /** data.binance.vision static dumps: no request weight, only timeouts, retries and the breaker. */
    DATA_ARCHIVE
}
