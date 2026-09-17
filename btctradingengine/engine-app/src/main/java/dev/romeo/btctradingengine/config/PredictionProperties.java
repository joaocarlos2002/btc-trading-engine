package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/** {@code prediction.*}: rule thresholds, filters, entry guards and the entry threshold/confirmation. */
@Validated
@ConfigurationProperties("prediction")
public record PredictionProperties(
        @Name("rsi.oversold") @NotNull BigDecimal rsiOversold,
        @Name("rsi.neutral.low") @NotNull BigDecimal rsiNeutralLow,
        @Name("rsi.neutral.high") @NotNull BigDecimal rsiNeutralHigh,
        @Name("rsi.overbought") @NotNull BigDecimal rsiOverbought,
        @Name("sma.distance.extreme") @NotNull BigDecimal smaDistanceExtreme,
        @Name("sma.distance.moderate") @NotNull BigDecimal smaDistanceModerate,
        @Name("atr.volatility.low") @NotNull BigDecimal atrVolatilityLow,
        @Name("atr.volatility.normal") @NotNull BigDecimal atrVolatilityNormal,
        @Name("atr.volatility.high") @NotNull BigDecimal atrVolatilityHigh,
        @Name("macd.histogram.strong.atr.ratio") @NotNull BigDecimal macdStrongHistogramAtrRatio,
        @Name("volatility.ratio.high") @NotNull BigDecimal volatilityRatioHigh,
        @Name("adx.trend.min") @NotNull BigDecimal adxTrendMin,
        @Name("adx.trend.strong") @NotNull BigDecimal adxTrendStrong,
        @Name("mfi.oversold") @NotNull BigDecimal mfiOversold,
        @Name("mfi.neutral.low") @NotNull BigDecimal mfiNeutralLow,
        @Name("mfi.neutral.high") @NotNull BigDecimal mfiNeutralHigh,
        @Name("mfi.overbought") @NotNull BigDecimal mfiOverbought,
        @Name("bollinger.squeeze.threshold") @NotNull BigDecimal bollingerSqueezeThreshold,
        @Name("regime.gating.enabled") boolean regimeGatingEnabled,
        @Name("vpin.filter.enabled") boolean vpinFilterEnabled,
        @Name("vpin.high") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "prediction.vpin.high must be in (0, 1]")
        @DecimalMax(value = "1", message = "prediction.vpin.high must be in (0, 1]")
        BigDecimal vpinHighThreshold,
        @Name("orderbook.filter.enabled") boolean orderBookFilterEnabled,
        @Name("orderbook.buy.min") @NotNull BigDecimal orderBookBuyMin,
        @Name("orderbook.sell.max") @NotNull BigDecimal orderBookSellMax,
        @Name("buy.threshold") double buyThreshold,
        @Name("sell.threshold") double sellThreshold,
        @Name("hold.min") double holdMin,
        @Name("hold.max") double holdMax,
        @Name("confirmation.snapshots") @Min(value = 1, message = "prediction.confirmation.snapshots must be positive")
        int confirmationSnapshots
) {
    @AssertTrue(message = "prediction.orderbook.buy.min must be lower than prediction.orderbook.sell.max")
    public boolean isOrderBookBuyMinBelowSellMax() {
        return orderBookBuyMin == null || orderBookSellMax == null || orderBookBuyMin.compareTo(orderBookSellMax) < 0;
    }

    @AssertTrue(message = "prediction.adx.trend.min cannot be above prediction.adx.trend.strong")
    public boolean isAdxTrendMinNotAboveStrong() {
        return adxTrendMin == null || adxTrendStrong == null || adxTrendMin.compareTo(adxTrendStrong) <= 0;
    }

    @AssertTrue(message = "prediction.mfi.oversold must be lower than prediction.mfi.overbought")
    public boolean isMfiOversoldBelowOverbought() {
        return mfiOversold == null || mfiOverbought == null || mfiOversold.compareTo(mfiOverbought) < 0;
    }

    @AssertTrue(message = "prediction.hold.min must be lower than hold.max")
    public boolean isHoldMinBelowMax() {
        return holdMin < holdMax;
    }

    @AssertTrue(message = "Prediction thresholds must be within [-1, 1] with buy positive and sell negative")
    public boolean isThresholdsInRange() {
        return buyThreshold > 0 && buyThreshold <= 1
                && sellThreshold < 0 && sellThreshold >= -1
                && holdMin >= -1 && holdMax <= 1;
    }
}
