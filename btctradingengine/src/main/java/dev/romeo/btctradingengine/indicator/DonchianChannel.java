package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Deque<BigDecimal> highs = new ArrayDeque<>();
    private final Deque<BigDecimal> lows = new ArrayDeque<>();

    public DonchianChannel(int period) {
        this.period = period;
    }

    public Optional<DonchianValue> update(CandleEvent candle) {
        highs.addLast(candle.high());
        lows.addLast(candle.low());

        if (highs.size() > period) {
            highs.removeFirst();
            lows.removeFirst();
        }
        if (highs.size() < period) {
            return Optional.empty();
        }

        BigDecimal upper = highest();
        BigDecimal lower = lowest();

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

    private BigDecimal highest() {
        BigDecimal highest = null;
        for (BigDecimal high : highs) {
            highest = highest == null ? high : highest.max(high);
        }
        return highest;
    }

    private BigDecimal lowest() {
        BigDecimal lowest = null;
        for (BigDecimal low : lows) {
            lowest = lowest == null ? low : lowest.min(low);
        }
        return lowest;
    }

    public record DonchianValue(
            BigDecimal upper,
            BigDecimal lower,
            BigDecimal position
    ) {}
}
