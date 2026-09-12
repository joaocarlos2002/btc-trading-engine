package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.adapter.CandleEventListener;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.indicator.AtrIndicator;
import dev.romeo.btctradingengine.indicator.Ema;
import dev.romeo.btctradingengine.indicator.MacdIndicator;
import dev.romeo.btctradingengine.indicator.Rsi;
import dev.romeo.btctradingengine.indicator.SmaIncremental;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

public class FeatureExtractor implements CandleEventListener {
    private static final Logger logger = LoggerFactory.getLogger(FeatureExtractor.class);

    private final FeatureBuffer buffer = new FeatureBuffer();
    private final SmaIncremental smaIncremental;
    private final Ema emaIncremental;
    private final Rsi rsiIncremental;
    private final MacdIndicator macdIndicator;
    private final AtrIndicator atrIndicator;
    private final FeatureEventListener listener;
    private final int volatilityShortPeriods;
    private final int volatilityLongPeriods;
    private final int volumeAveragePeriods;

    public FeatureExtractor(int smaPeriod, int emaPeriod, int rsiPeriod, FeatureEventListener listener) {
        this(smaPeriod, emaPeriod, rsiPeriod, Config.getAtrPeriod(),
                Config.getMacdFastPeriod(), Config.getMacdSlowPeriod(), Config.getMacdSignalPeriod(),
                Config.getVolatilityShortPeriods(), Config.getVolatilityLongPeriods(), Config.getVolumeAveragePeriods(),
                listener);
    }

    /** Allows overriding every period without touching global Config - used by on-demand backtests. */
    public FeatureExtractor(int smaPeriod, int emaPeriod, int rsiPeriod, int atrPeriod,
                             int macdFastPeriod, int macdSlowPeriod, int macdSignalPeriod,
                             int volatilityShortPeriods, int volatilityLongPeriods, int volumeAveragePeriods,
                             FeatureEventListener listener) {
        this.smaIncremental = new SmaIncremental(smaPeriod);
        this.emaIncremental = new Ema(emaPeriod);
        this.rsiIncremental = new Rsi(rsiPeriod);
        this.macdIndicator = new MacdIndicator(macdFastPeriod, macdSlowPeriod, macdSignalPeriod);
        this.atrIndicator = new AtrIndicator(atrPeriod);
        this.volatilityShortPeriods = volatilityShortPeriods;
        this.volatilityLongPeriods = volatilityLongPeriods;
        this.volumeAveragePeriods = volumeAveragePeriods;
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

            Optional<BigDecimal> sma = smaIncremental.updateSum(candle.close());
            BigDecimal ema = emaIncremental.update(candle.close());
            Optional<BigDecimal> rsi = rsiIncremental.update(candle.close());
            var macd = macdIndicator.update(candle.close());
            var atr = atrIndicator.update(candle);

            FeatureVector features = extractFeatures(candle, sma, ema, rsi, macd, atr);
            eventListener.onEvent(features);

        } catch (Exception e) {
            logger.error("Error extracting features for candle: {}", candle.openTime(), e);
        }
    }

    private FeatureVector extractFeatures(CandleEvent candle,
                                          Optional<BigDecimal> sma,
                                          BigDecimal ema,
                                          Optional<BigDecimal> rsi,
                                          Optional<MacdIndicator.MacdValue> macd,
                                          Optional<BigDecimal> atr) {

        BigDecimal returnPct1m = calculateReturn(candle);
        BigDecimal volatility5m = buffer.volatility(volatilityShortPeriods).orElse(BigDecimal.ZERO);
        BigDecimal volatility20m = buffer.volatility(volatilityLongPeriods).orElse(BigDecimal.ZERO);
        BigDecimal smaDistance = calculateSmaDistance(candle.close(), sma);
        BigDecimal emaDistance = calculateEmaDistance(candle.close(), ema);
        BigDecimal rsiValue = rsi.orElse(BigDecimal.valueOf(50));

        BigDecimal macdValue = macd.map(MacdIndicator.MacdValue::macd).orElse(BigDecimal.ZERO);
        BigDecimal macdSignal = macd.map(MacdIndicator.MacdValue::signal).orElse(BigDecimal.ZERO);
        BigDecimal atrValue = atr.orElse(BigDecimal.ZERO);

        BigDecimal volumeRatio = calculateVolumeRatio(candle);
        BigDecimal highLowRatio = calculateHighLowRatio(candle);
        BigDecimal closePosition = calculateClosePosition(candle);

        ZonedDateTime zdt = candle.closeTime().atZone(ZoneOffset.UTC);
        int hourOfDay = zdt.getHour();
        int dayOfWeek = zdt.getDayOfWeek().getValue();

        return FeatureVector.builder()
                .instrument(candle.instrument())
                .timestamp(candle.closeTime())
                .returnPct1m(returnPct1m)
                .volatility5m(volatility5m)
                .volatility20m(volatility20m)
                .smaDistance(smaDistance)
                .emaDistance(emaDistance)
                .rsiValue(rsiValue)
                .macdValue(macdValue)
                .macdSignal(macdSignal)
                .atrValue(atrValue)
                .volumeRatio(volumeRatio)
                .highLowRatio(highLowRatio)
                .closePosition(closePosition)
                .hourOfDay(hourOfDay)
                .dayOfWeek(dayOfWeek)
                .price(candle.close())
                .tickCount(candle.tickCount())
                .build();
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

    private BigDecimal calculateSmaDistance(BigDecimal close, Optional<BigDecimal> sma) {
        if (sma.isEmpty() || close.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return close.subtract(sma.get())
                .divide(close, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateEmaDistance(BigDecimal close, BigDecimal ema) {
        if (close.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return close.subtract(ema)
                .divide(close, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateVolumeRatio(CandleEvent candle) {
        var avgVolume = buffer.averageVolume(volumeAveragePeriods);
        if (avgVolume.isEmpty() || avgVolume.get().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        return candle.volume()
                .divide(avgVolume.get(), 8, RoundingMode.HALF_UP);
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

