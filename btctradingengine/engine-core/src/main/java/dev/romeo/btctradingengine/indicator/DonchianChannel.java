package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Donchian Channel: highest high and lowest low over the window, plus the close's normalized
 * position inside the channel [0-1].
 *
 * Price context only: it describes where price sits in its recent range and does not produce a
 * signal on its own. Whether a channel touch means breakout or range rejection is decided by the
 * regime layer (ADX / ATR% / BB Width), not here.
 */
public class DonchianChannel {
    private final int period;
    private final RollingExtremum highs;
    private final RollingExtremum lows;

    public DonchianChannel(int period) {
        this.period = period;
        this.highs = RollingExtremum.max(period);
        this.lows = RollingExtremum.min(period);
    }

    public Optional<DonchianValue> update(CandleEvent candle) {
        highs.add(candle.high());
        lows.add(candle.low());

        if (highs.size() < period) {
            return Optional.empty();
        }

        BigDecimal upper = highs.value();
        BigDecimal lower = lows.value();

        return Optional.of(new DonchianValue(upper, lower, position(candle.close(), upper, lower)));
    }

    /** A fully flat channel leaves the position undefined, so it reports the midpoint (as %B does). */
    private BigDecimal position(BigDecimal close, BigDecimal upper, BigDecimal lower) {
        BigDecimal channelRange = upper.subtract(lower);
        if (channelRange.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(0.5).setScale(8, RoundingMode.HALF_EVEN);
        }
        return close.subtract(lower)
                .divide(channelRange, 8, RoundingMode.HALF_EVEN);
    }

    public record DonchianValue(
            BigDecimal upper,
            BigDecimal lower,
            BigDecimal position
    ) {}
}
