package dev.romeo.btctradingengine.model;

import java.math.BigDecimal;

/**
 * Candle volume split by aggressor side (issue #11), plus the part of it that came from large trades.
 *
 * The source says how much of this is real, because a zero volume is ambiguous on its own - "no
 * data" and "nobody bought" would otherwise look the same:
 * <ul>
 *   <li>NONE: no aggressor data at all, every volume is 0 and must be ignored</li>
 *   <li>KLINE: Binance klines give the taker buy volume but no trade sizes, so large* is 0</li>
 *   <li>TRADES: built from aggTrades (live, or the daily dumps in backtests), both the side and the
 *       size split are known</li>
 * </ul>
 */
public record TradeFlow(
        Source source,
        BigDecimal takerBuyVolume,        // base volume where the buyer was the aggressor
        BigDecimal takerSellVolume,       // base volume where the seller was the aggressor
        BigDecimal largeBuyVolume,        // part of takerBuyVolume from trades >= the large notional
        BigDecimal largeSellVolume        // part of takerSellVolume from trades >= the large notional
) {

    public enum Source { NONE, KLINE, TRADES }

    public static TradeFlow none() {
        return new TradeFlow(Source.NONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Klines only report the taker buy volume; the rest of the candle volume was sell aggression. */
    public static TradeFlow fromKline(BigDecimal volume, BigDecimal takerBuyVolume) {
        return new TradeFlow(Source.KLINE, takerBuyVolume, volume.subtract(takerBuyVolume),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static TradeFlow fromTrades(BigDecimal takerBuyVolume, BigDecimal takerSellVolume,
                                       BigDecimal largeBuyVolume, BigDecimal largeSellVolume) {
        return new TradeFlow(Source.TRADES, takerBuyVolume, takerSellVolume, largeBuyVolume, largeSellVolume);
    }

    public boolean hasTakerSplit() {
        return source != Source.NONE;
    }

    public boolean hasSizeSplit() {
        return source == Source.TRADES;
    }

    public BigDecimal delta() {
        return takerBuyVolume.subtract(takerSellVolume);
    }
}
