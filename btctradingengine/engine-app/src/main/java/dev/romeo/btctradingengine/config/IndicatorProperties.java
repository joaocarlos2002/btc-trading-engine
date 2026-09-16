package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * {@code indicator.*}: indicator periods, in candles (scaled x15 for 1m candles, see
 * application.properties).
 */
@Validated
@ConfigurationProperties("indicator")
public record IndicatorProperties(
        @Name("sma.period") @Min(value = 1, message = "indicator.sma.period must be positive") int smaPeriod,
        @Name("ema.period") @Min(value = 1, message = "indicator.ema.period must be positive") int emaPeriod,
        @Name("rsi.period") @Min(value = 1, message = "indicator.rsi.period must be positive") int rsiPeriod,
        @Name("atr.period") @Min(value = 1, message = "indicator.atr.period must be positive") int atrPeriod,
        @Name("macd.fast.period") @Min(value = 1, message = "indicator.macd.fast.period must be positive")
        int macdFastPeriod,
        @Name("macd.slow.period") @Min(value = 1, message = "indicator.macd.slow.period must be positive")
        int macdSlowPeriod,
        @Name("macd.signal.period") @Min(value = 1, message = "indicator.macd.signal.period must be positive")
        int macdSignalPeriod,
        @Name("adx.period") @Min(value = 1, message = "indicator.adx.period must be positive") int adxPeriod,
        @Name("bollinger.period") @Min(value = 1, message = "indicator.bollinger.period must be positive")
        int bollingerPeriod,
        @Name("bollinger.stddev") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "indicator.bollinger.stddev must be positive")
        BigDecimal bollingerStdDev,
        @Name("mfi.period") @Min(value = 1, message = "indicator.mfi.period must be positive") int mfiPeriod,
        @Name("donchian.period") @Min(value = 1, message = "indicator.donchian.period must be positive")
        int donchianPeriod,
        /* daily or rolling; anything else reads as daily, as it always has (VwapAnchor.fromProperty). */
        @Name("vwap.anchor") String vwapAnchor,
        @Name("vwap.rolling.periods") @Min(value = 1, message = "indicator.vwap.rolling.periods must be positive")
        int vwapRollingPeriods,
        @Name("priceaction.lookback") @Min(value = 1, message = "indicator.priceaction.lookback must be positive")
        int priceActionLookback,
        @Name("priceaction.swing.strength")
        @Min(value = 1, message = "indicator.priceaction.swing.strength must be positive")
        int priceActionSwingStrength,
        @Name("cvd.period") @Min(value = 1, message = "indicator.cvd.period must be positive") int cvdPeriod,
        @Name("ema.slope.periods") @Min(value = 1, message = "indicator.ema.slope.periods must be positive")
        int emaSlopePeriods,
        @Name("vpin.buckets") @Min(value = 1, message = "indicator.vpin.buckets must be positive") int vpinBuckets,
        @Name("vpin.bucket.candles") @Min(value = 1, message = "indicator.vpin.bucket.candles must be positive")
        int vpinBucketCandles
) {
    @AssertTrue(message = "indicator.macd.fast.period must be lower than slow.period")
    public boolean isMacdFastBelowSlow() {
        return macdFastPeriod < macdSlowPeriod;
    }
}
