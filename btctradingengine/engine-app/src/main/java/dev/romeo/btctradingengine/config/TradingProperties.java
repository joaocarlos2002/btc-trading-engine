package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code trading.*}: risk per position, real-mode switches and position sizing.
 *
 * <p>{@code trading.oco.enabled} and {@code trading.sizing.strategy} are overridden by
 * BTC_ENGINE_TRADING_OCO_ENABLED / BTC_ENGINE_TRADING_SIZING_STRATEGY (see application.properties).
 */
@Validated
@ConfigurationProperties("trading")
public record TradingProperties(
        @Name("target.percent") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "Trading target and stop loss must be positive")
        BigDecimal targetPercent,
        @Name("stop.loss.percent") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "Trading target and stop loss must be positive")
        BigDecimal stopLossPercent,
        @Name("real.enabled") boolean realEnabled,
        /* Safety guard: required when real trading points at a non-testnet Binance endpoint. */
        @Name("confirm.mainnet") boolean confirmMainnet,
        @Name("initial.capital.usdt") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "Trading capital and max drawdown must be positive")
        BigDecimal initialCapitalUsdt,
        @Name("max.drawdown.percent") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "Trading capital and max drawdown must be positive")
        BigDecimal maxDrawdownPercent,
        @Name("max.data.staleness.seconds")
        @Min(value = 1, message = "trading.max.data.staleness.seconds must be positive")
        long maxDataStalenessSeconds,
        @Name("allow.short") boolean allowShort,
        @Name("oco.enabled") boolean ocoEnabled,
        @Name("oco.stop.limit.offset.percent") @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "trading.oco.stop.limit.offset.percent must be in (0, 10)")
        @DecimalMax(value = "10", inclusive = false, message = "trading.oco.stop.limit.offset.percent must be in (0, 10)")
        BigDecimal ocoStopLimitOffsetPercent,
        @Name("sizing.strategy") String sizingStrategy,
        @Name("sizing.fraction") @NotNull BigDecimal sizingFraction,
        @Name("sizing.atr.risk.percent") @NotNull BigDecimal sizingAtrRiskPercent,
        @Name("sizing.atr.multiplier") @NotNull BigDecimal sizingAtrMultiplier,
        @Name("sizing.kelly.fraction") @NotNull BigDecimal sizingKellyFraction,
        @Name("sizing.kelly.min.trades") int sizingKellyMinTrades
) {
    public Duration maxDataStaleness() {
        return Duration.ofSeconds(maxDataStalenessSeconds);
    }
}
