package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/** {@code feature.*}: feature windows, absorption and the large-trade threshold of the flow features. */
@Validated
@ConfigurationProperties("feature")
public record FeatureProperties(
        @Name("volatility.short.periods") @Min(value = 1, message = "feature.volatility.short.periods must be positive")
        int volatilityShortPeriods,
        @Name("volatility.long.periods") @Min(value = 1, message = "feature.volatility.long.periods must be positive")
        int volatilityLongPeriods,
        @Name("volume.average.periods") @Min(value = 1, message = "feature.volume.average.periods must be positive")
        int volumeAveragePeriods,
        @Name("absorption.delta.min") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "feature.absorption.delta.min must be in (0, 1]")
        @DecimalMax(value = "1", message = "feature.absorption.delta.min must be in (0, 1]")
        BigDecimal absorptionDeltaMin,
        @Name("absorption.volume.ratio.min") @NotNull
        @DecimalMin(value = "0", inclusive = false,
                message = "feature.absorption.volume.ratio.min must be positive and max.move.atr non-negative")
        BigDecimal absorptionVolumeRatioMin,
        @Name("absorption.max.move.atr") @NotNull
        @DecimalMin(value = "0",
                message = "feature.absorption.volume.ratio.min must be positive and max.move.atr non-negative")
        BigDecimal absorptionMaxMoveAtr,
        @Name("absorption.window") @Min(value = 1, message = "feature.absorption.window must be positive")
        int absorptionWindow,
        @Name("flow.large.trade.notional") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "feature.flow.large.trade.notional must be positive")
        BigDecimal largeTradeNotional
) {
}
