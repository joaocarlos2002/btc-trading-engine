package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Volume weighted average price, used as price context (distance from VWAP), not as a signal.
 *
 * DAILY anchors the accumulator to the UTC session: it resets as soon as the UTC day of
 * candle.closeTime() changes, and therefore has no warmup - a single candle already yields a
 * VWAP. ROLLING keeps a sliding window of N candles instead and stays empty until the window is
 * full, which is what the backtest needs to avoid a partially accumulated session at the start
 * of an arbitrary date range.
 */
public class Vwap {
    private final VwapAnchor anchor;
    private final int rollingPeriods;

    private final Deque<Bar> window = new ArrayDeque<>();
    private BigDecimal cumulativePriceVolume = BigDecimal.ZERO;
    private BigDecimal cumulativeVolume = BigDecimal.ZERO;
    private LocalDate currentSession;

    public Vwap(VwapAnchor anchor, int rollingPeriods) {
        this.anchor = anchor;
        this.rollingPeriods = rollingPeriods;
    }

    public Optional<BigDecimal> update(CandleEvent candle) {
        BigDecimal typicalPrice = typicalPrice(candle);
        BigDecimal priceVolume = typicalPrice.multiply(candle.volume());

        return anchor == VwapAnchor.ROLLING
                ? updateRolling(typicalPrice, priceVolume, candle.volume())
                : updateDaily(candle, typicalPrice, priceVolume);
    }

    private Optional<BigDecimal> updateDaily(CandleEvent candle, BigDecimal typicalPrice, BigDecimal priceVolume) {
        LocalDate session = LocalDate.ofInstant(candle.closeTime(), ZoneOffset.UTC);
        if (!session.equals(currentSession)) {
            currentSession = session;
            cumulativePriceVolume = BigDecimal.ZERO;
            cumulativeVolume = BigDecimal.ZERO;
        }

        cumulativePriceVolume = cumulativePriceVolume.add(priceVolume);
        cumulativeVolume = cumulativeVolume.add(candle.volume());

        return Optional.of(weightedAverage(typicalPrice));
    }

    private Optional<BigDecimal> updateRolling(BigDecimal typicalPrice, BigDecimal priceVolume, BigDecimal volume) {
        window.addLast(new Bar(priceVolume, volume));
        cumulativePriceVolume = cumulativePriceVolume.add(priceVolume);
        cumulativeVolume = cumulativeVolume.add(volume);

        if (window.size() > rollingPeriods) {
            Bar expired = window.removeFirst();
            cumulativePriceVolume = cumulativePriceVolume.subtract(expired.priceVolume());
            cumulativeVolume = cumulativeVolume.subtract(expired.volume());
        }
        if (window.size() < rollingPeriods) {
            return Optional.empty();
        }
        return Optional.of(weightedAverage(typicalPrice));
    }

    /** Zero traded volume leaves VWAP undefined; the candle's own typical price is the best proxy. */
    private BigDecimal weightedAverage(BigDecimal typicalPrice) {
        if (cumulativeVolume.compareTo(BigDecimal.ZERO) == 0) {
            return typicalPrice;
        }
        return cumulativePriceVolume.divide(cumulativeVolume, 8, RoundingMode.HALF_EVEN);
    }

    private BigDecimal typicalPrice(CandleEvent candle) {
        return candle.high()
                .add(candle.low())
                .add(candle.close())
                .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_EVEN);
    }

    private record Bar(BigDecimal priceVolume, BigDecimal volume) {}
}
