package dev.romeo.btctradingengine.model;

/** Which side of a trade crossed the spread (took liquidity). */
public enum AggressorSide {
    BUY,
    SELL,
    /** The source does not report the side (e.g. the fake source or old persisted ticks). */
    UNKNOWN;

    /**
     * Binance's aggTrade "m" flag is true when the BUYER was the maker, which means the SELLER was
     * the aggressor. It reads backwards at first glance and inverting it flips the sign of every CVD.
     */
    public static AggressorSide fromBuyerMaker(boolean buyerMaker) {
        return buyerMaker ? SELL : BUY;
    }
}
