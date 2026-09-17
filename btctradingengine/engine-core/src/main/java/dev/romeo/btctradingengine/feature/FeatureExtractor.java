package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.port.CandleEventListener;
import dev.romeo.btctradingengine.indicator.Absorption;
import dev.romeo.btctradingengine.indicator.AdxIndicator;
import dev.romeo.btctradingengine.indicator.AtrIndicator;
import dev.romeo.btctradingengine.indicator.BollingerBands;
import dev.romeo.btctradingengine.indicator.CumulativeVolumeDelta;
import dev.romeo.btctradingengine.indicator.DonchianChannel;
import dev.romeo.btctradingengine.indicator.Ema;
import dev.romeo.btctradingengine.indicator.MacdIndicator;
import dev.romeo.btctradingengine.indicator.MfiIndicator;
import dev.romeo.btctradingengine.indicator.PriceAction;
import dev.romeo.btctradingengine.indicator.Rsi;
import dev.romeo.btctradingengine.indicator.SmaIncremental;
import dev.romeo.btctradingengine.indicator.Vpin;
import dev.romeo.btctradingengine.indicator.Vwap;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public class FeatureExtractor implements CandleEventListener {
    private static final Logger logger = LoggerFactory.getLogger(FeatureExtractor.class);

    /** Neutral RSI/MFI value reported while the oscillator is still warming up. */
    private static final BigDecimal NEUTRAL_OSCILLATOR = BigDecimal.valueOf(50);

    private final FeatureBuffer buffer;
    private final SmaIncremental smaIncremental;
    private final Ema emaIncremental;
    private final Rsi rsiIncremental;
    private final MacdIndicator macdIndicator;
    private final AtrIndicator atrIndicator;
    private final AdxIndicator adxIndicator;
    private final BollingerBands bollingerBands;
    private final Vwap vwap;
    private final MfiIndicator mfiIndicator;
    private final DonchianChannel donchianChannel;
    private final PriceAction priceAction;
    private final CumulativeVolumeDelta cumulativeVolumeDelta;
    private final Vpin vpin;
    private final Absorption absorption;
    private final DerivativesLookup derivatives;
    private final OrderBookLookup orderBook;
    private final Deque<BigDecimal> emaHistory = new ArrayDeque<>();
    private final int emaSlopePeriods;
    private final FeatureEventListener listener;
    private final int volatilityShortPeriods;
    private final int volatilityLongPeriods;
    private final int volumeAveragePeriods;

    public FeatureExtractor(int smaPeriod, int emaPeriod, int rsiPeriod, FeatureEventListener listener) {
        this(IndicatorPeriods.defaults().withCorePeriods(smaPeriod, emaPeriod, rsiPeriod), listener);
    }

    /** Allows overriding every period without touching global Config - used by on-demand backtests. */
    public FeatureExtractor(IndicatorPeriods periods, FeatureEventListener listener) {
        this(periods, DerivativesLookup.NONE, listener);
    }

    /** Derivatives do not come out of the candle stream, so they are looked up per candle. */
    public FeatureExtractor(IndicatorPeriods periods, DerivativesLookup derivatives, FeatureEventListener listener) {
        this(periods, derivatives, OrderBookLookup.NONE, listener);
    }

    /** The order book is also polled outside the candle stream (issue #10), so it is looked up per candle too. */
    public FeatureExtractor(IndicatorPeriods periods, DerivativesLookup derivatives, OrderBookLookup orderBook,
                            FeatureEventListener listener) {
        this.derivatives = Objects.requireNonNull(derivatives, "derivatives");
        this.orderBook = Objects.requireNonNull(orderBook, "orderBook");
        this.smaIncremental = new SmaIncremental(periods.sma());
        this.emaIncremental = new Ema(periods.ema());
        this.rsiIncremental = new Rsi(periods.rsi());
        this.macdIndicator = new MacdIndicator(periods.macdFast(), periods.macdSlow(), periods.macdSignal());
        this.atrIndicator = new AtrIndicator(periods.atr());
        this.adxIndicator = new AdxIndicator(periods.adx());
        this.bollingerBands = new BollingerBands(periods.bollinger(), periods.bollingerStdDev());
        this.vwap = new Vwap(periods.vwapAnchor(), periods.vwapRollingPeriods());
        this.mfiIndicator = new MfiIndicator(periods.mfi());
        this.donchianChannel = new DonchianChannel(periods.donchian());
        this.priceAction = new PriceAction(periods.priceActionLookback(), periods.priceActionSwingStrength());
        this.cumulativeVolumeDelta = new CumulativeVolumeDelta(periods.cvd());
        this.vpin = new Vpin(periods.vpinBuckets(), periods.vpinBucketCandles());
        this.absorption = new Absorption(periods.absorptionDeltaMin(), periods.absorptionVolumeRatioMin(),
                periods.absorptionMaxMoveAtr(), periods.absorptionWindow());
        this.emaSlopePeriods = periods.emaSlope();
        this.volatilityShortPeriods = periods.volatilityShort();
        this.volatilityLongPeriods = periods.volatilityLong();
        this.volumeAveragePeriods = periods.volumeAverage();
        // Sized to the largest window it serves, otherwise those features never leave warmup (issue #71)
        this.buffer = new FeatureBuffer(Math.max(volatilityShortPeriods, Math.max(volatilityLongPeriods, volumeAveragePeriods)));
        this.listener = listener;
    }

    @Override
    public void onEvent(CandleEvent candle) {
        process(candle, listener);
    }

    public void warmUp(CandleEvent candle, FeatureEventListener warmupListener) {
        process(candle, warmupListener);
    }

    private void process(CandleEvent candle, FeatureEventListener eventListener) {
        try {
            buffer.add(candle);
            eventListener.onEvent(extractFeatures(candle));
        } catch (Exception e) {
            logger.error("Error extracting features for candle: {}", candle.openTime(), e);
        }
    }

    /**
     * Feeds every indicator exactly once with this candle and assembles the vector. Both the live
     * path (onEvent) and the warmup path (warmUp) come through here, so the two can never diverge
     * on which indicators got updated.
     */
    private FeatureVector extractFeatures(CandleEvent candle) {
        BigDecimal close = candle.close();

        BigDecimal sma = smaIncremental.updateSum(close).orElse(BigDecimal.ZERO);
        BigDecimal ema = emaIncremental.update(close);
        BigDecimal emaSlope = calculateEmaSlope(ema);
        BigDecimal rsi = rsiIncremental.update(close).orElse(NEUTRAL_OSCILLATOR);
        var macd = macdIndicator.update(close);
        BigDecimal atr = atrIndicator.update(candle).orElse(BigDecimal.ZERO);
        var adx = adxIndicator.update(candle);
        var bollinger = bollingerBands.update(close);
        BigDecimal vwapValue = vwap.update(candle).orElse(BigDecimal.ZERO);
        BigDecimal mfi = mfiIndicator.update(candle).orElse(NEUTRAL_OSCILLATOR);
        var donchian = donchianChannel.update(candle);
        var priceActionValue = priceAction.update(candle);
        var cvd = cumulativeVolumeDelta.update(candle);
        BigDecimal vpinValue = vpin.update(candle);
        BigDecimal volumeRatio = calculateVolumeRatio(candle);
        var absorptionValue = absorption.update(candle, cvd.deltaRatio(), volumeRatio, atr);

        ZonedDateTime zdt = candle.closeTime().atZone(ZoneOffset.UTC);

        return FeatureVector.builder()
                .instrument(candle.instrument())
                .timestamp(candle.closeTime())

                .returnPct1m(calculateReturn(candle))
                .volatility5m(buffer.volatility(volatilityShortPeriods).orElse(BigDecimal.ZERO))
                .volatility20m(buffer.volatility(volatilityLongPeriods).orElse(BigDecimal.ZERO))
                .smaDistance(percentDistance(close, sma))
                .emaDistance(percentDistance(close, ema))
                .rsiValue(rsi)
                .macdValue(macd.map(MacdIndicator.MacdValue::macd).orElse(BigDecimal.ZERO))
                .macdSignal(macd.map(MacdIndicator.MacdValue::signal).orElse(BigDecimal.ZERO))
                .atrValue(atr)
                .volumeRatio(volumeRatio)
                .highLowRatio(calculateHighLowRatio(candle))
                .closePosition(calculateClosePosition(candle))
                .hourOfDay(zdt.getHour())
                .dayOfWeek(zdt.getDayOfWeek().getValue())

                .adx(adx.map(AdxIndicator.AdxValue::adx).orElse(BigDecimal.ZERO))
                .plusDi(adx.map(AdxIndicator.AdxValue::plusDi).orElse(BigDecimal.ZERO))
                .minusDi(adx.map(AdxIndicator.AdxValue::minusDi).orElse(BigDecimal.ZERO))
                .atrPercent(calculateAtrPercent(atr, close))
                .bbWidth(bollinger.map(BollingerBands.BollingerValue::width).orElse(BigDecimal.ZERO))
                .emaSlope(emaSlope)

                .vwap(vwapValue)
                .vwapDistance(percentDistanceFrom(close, vwapValue))
                .bbPercentB(bollinger.map(BollingerBands.BollingerValue::percentB).orElse(BigDecimal.ZERO))
                .donchianUpper(donchian.map(DonchianChannel.DonchianValue::upper).orElse(BigDecimal.ZERO))
                .donchianLower(donchian.map(DonchianChannel.DonchianValue::lower).orElse(BigDecimal.ZERO))
                .donchianPosition(donchian.map(DonchianChannel.DonchianValue::position).orElse(BigDecimal.ZERO))

                .mfi(mfi)
                .volumeDelta(cvd.volumeDelta())
                .deltaRatio(cvd.deltaRatio())
                .cvd(cvd.cvd())
                .cvdRatio(cvd.cvdRatio())
                .largeCvd(cvd.largeCvd())
                .largeVolumeShare(cvd.largeVolumeShare())
                .vpin(vpinValue)
                .absorption(absorptionValue.strength())
                .absorptionSum(absorptionValue.windowSum())
                .orderBookImbalance(orderBook.imbalanceFor(candle))

                .priceAction(PriceActionFeatures.from(priceActionValue))
                .deriv(derivatives.featuresFor(candle))

                .price(close)
                .tickCount(candle.tickCount())
                .build();
    }

    /**
     * % change of the EMA over the last emaSlopePeriods candles. Zero until that much history exists,
     * the same "not available yet" convention the other regime fields use.
     */
    private BigDecimal calculateEmaSlope(BigDecimal ema) {
        emaHistory.addLast(ema);
        if (emaHistory.size() > emaSlopePeriods + 1) {
            emaHistory.removeFirst();
        }
        if (emaHistory.size() <= emaSlopePeriods) {
            return BigDecimal.ZERO;
        }
        return percentDistanceFrom(ema, emaHistory.getFirst());
    }

    private BigDecimal calculateReturn(CandleEvent candle) {
        if (candle.open().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return candle.close()
                .subtract(candle.open())
                .divide(candle.open(), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Distance from a moving average as a percentage of the close. A zero reference means the
     * indicator is still warming up (no real price is zero), so the distance is reported as zero.
     */
    private BigDecimal percentDistance(BigDecimal close, BigDecimal reference) {
        if (close.compareTo(BigDecimal.ZERO) == 0 || reference.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return close.subtract(reference)
                .divide(close, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /** Distance as a percentage of the reference itself, which is how VWAP distance is defined. */
    private BigDecimal percentDistanceFrom(BigDecimal close, BigDecimal reference) {
        if (reference.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return close.subtract(reference)
                .divide(reference, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * ATR as a percentage of price. AtrRule used to compute this inline; it is a regime feature
     * consumed by more than one rule, so it lives in the vector now and is calculated once.
     */
    private BigDecimal calculateAtrPercent(BigDecimal atrValue, BigDecimal close) {
        if (close.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return atrValue.divide(close, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateVolumeRatio(CandleEvent candle) {
        BigDecimal averageVolume = buffer.averageVolume(volumeAveragePeriods).orElse(BigDecimal.ZERO);
        if (averageVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        return candle.volume()
                .divide(averageVolume, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateHighLowRatio(CandleEvent candle) {
        if (candle.low().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return candle.high()
                .subtract(candle.low())
                .divide(candle.low(), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateClosePosition(CandleEvent candle) {
        BigDecimal range = candle.high().subtract(candle.low());
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return candle.close()
                .subtract(candle.low())
                .divide(range, 8, RoundingMode.HALF_UP);
    }
}
