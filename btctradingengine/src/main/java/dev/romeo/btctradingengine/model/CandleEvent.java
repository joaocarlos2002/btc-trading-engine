package dev.romeo.btctradingengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CandleEvent(
        String instrument,
        Instant openTime,
        Instant closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        int tickCount,
        TradeFlow flow
) {

    /** A candle whose source reports no aggressor side. */
    public CandleEvent(String instrument, Instant openTime, Instant closeTime,
                       BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                       BigDecimal volume, int tickCount) {
        this(instrument, openTime, closeTime, open, high, low, close, volume, tickCount, TradeFlow.none());
    }
}
