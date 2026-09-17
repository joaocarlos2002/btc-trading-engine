package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * The original feature set: price action, the classic indicators and calendar context.
 * These are the fields that feed the directional score (RSI/SMA/MACD rules).
 */
public record CoreFeatures(
        BigDecimal returnPct1m,           // (close - open) / open * 100
        BigDecimal volatility5m,          // StdDev das ultimas 5 velas
        BigDecimal volatility20m,         // StdDev das ultimas 20 velas
        BigDecimal smaDistance,           // (close - SMA) / close * 100
        BigDecimal emaDistance,           // (close - EMA) / close * 100
        BigDecimal rsiValue,              // RSI [0-100]

        BigDecimal macdValue,             // MACD (EMA fast - EMA slow)
        BigDecimal macdSignal,            // EMA signal(MACD)
        BigDecimal atrValue,              // ATR

        BigDecimal volumeRatio,           // volume_atual / volume_medio
        BigDecimal highLowRatio,          // (high - low) / low * 100
        BigDecimal closePosition,         // (close - low) / (high - low) [0-1]

        int hourOfDay,                    // 0-23 (para padroes diarios)
        int dayOfWeek                     // 1-7 (seg-dom)
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BigDecimal returnPct1m = BigDecimal.ZERO;
        private BigDecimal volatility5m = BigDecimal.ZERO;
        private BigDecimal volatility20m = BigDecimal.ZERO;
        private BigDecimal smaDistance = BigDecimal.ZERO;
        private BigDecimal emaDistance = BigDecimal.ZERO;
        private BigDecimal rsiValue = BigDecimal.ZERO;
        private BigDecimal macdValue = BigDecimal.ZERO;
        private BigDecimal macdSignal = BigDecimal.ZERO;
        private BigDecimal atrValue = BigDecimal.ZERO;
        private BigDecimal volumeRatio = BigDecimal.ZERO;
        private BigDecimal highLowRatio = BigDecimal.ZERO;
        private BigDecimal closePosition = BigDecimal.ZERO;
        private int hourOfDay;
        private int dayOfWeek;

        public Builder returnPct1m(BigDecimal returnPct1m) { this.returnPct1m = returnPct1m; return this; }
        public Builder volatility5m(BigDecimal volatility5m) { this.volatility5m = volatility5m; return this; }
        public Builder volatility20m(BigDecimal volatility20m) { this.volatility20m = volatility20m; return this; }
        public Builder smaDistance(BigDecimal smaDistance) { this.smaDistance = smaDistance; return this; }
        public Builder emaDistance(BigDecimal emaDistance) { this.emaDistance = emaDistance; return this; }
        public Builder rsiValue(BigDecimal rsiValue) { this.rsiValue = rsiValue; return this; }
        public Builder macdValue(BigDecimal macdValue) { this.macdValue = macdValue; return this; }
        public Builder macdSignal(BigDecimal macdSignal) { this.macdSignal = macdSignal; return this; }
        public Builder atrValue(BigDecimal atrValue) { this.atrValue = atrValue; return this; }
        public Builder volumeRatio(BigDecimal volumeRatio) { this.volumeRatio = volumeRatio; return this; }
        public Builder highLowRatio(BigDecimal highLowRatio) { this.highLowRatio = highLowRatio; return this; }
        public Builder closePosition(BigDecimal closePosition) { this.closePosition = closePosition; return this; }
        public Builder hourOfDay(int hourOfDay) { this.hourOfDay = hourOfDay; return this; }
        public Builder dayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; return this; }

        public CoreFeatures build() {
            return new CoreFeatures(
                    returnPct1m, volatility5m, volatility20m,
                    smaDistance, emaDistance, rsiValue,
                    macdValue, macdSignal, atrValue,
                    volumeRatio, highLowRatio, closePosition,
                    hourOfDay, dayOfWeek
            );
        }
    }
}
