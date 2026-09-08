package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;
import java.time.Instant;

public record FeatureVector(
        String instrument,
        Instant timestamp,

        BigDecimal returnPct1m,           // (close - open) / open * 100
        BigDecimal volatility5m,          // StdDev Ãºltimas 5 velas
        BigDecimal volatility20m,         // StdDev Ãºltimas 20 velas
        BigDecimal smaDistance,           // (close - SMA50) / close * 100
        BigDecimal emaDistance,           // (close - EMA26) / close * 100
        BigDecimal rsiValue,              // RSI(14) [0-100]

        BigDecimal macdValue,             // MACD (EMA12 - EMA26)
        BigDecimal macdSignal,            // EMA9(MACD)
        BigDecimal atrValue,              // ATR(14)

        BigDecimal volumeRatio,           // volume_atual / volume_mÃ©dia
        BigDecimal highLowRatio,          // (high - low) / low * 100
        BigDecimal closePosition,         // (close - low) / (high - low) [0-1]

        int hourOfDay,                    // 0-23 (para padrÃµes diÃ¡rios)
        int dayOfWeek,                    // 1-7 (seg-dom)

        BigDecimal price,                 // close price (referÃªncia)
        int tickCount                     // ticks na vela
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String instrument;
        private Instant timestamp;
        private BigDecimal returnPct1m;
        private BigDecimal volatility5m;
        private BigDecimal volatility20m;
        private BigDecimal smaDistance;
        private BigDecimal emaDistance;
        private BigDecimal rsiValue;
        private BigDecimal macdValue;
        private BigDecimal macdSignal;
        private BigDecimal atrValue;
        private BigDecimal volumeRatio;
        private BigDecimal highLowRatio;
        private BigDecimal closePosition;
        private int hourOfDay;
        private int dayOfWeek;
        private BigDecimal price;
        private int tickCount;

        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
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
        public Builder price(BigDecimal price) { this.price = price; return this; }
        public Builder tickCount(int tickCount) { this.tickCount = tickCount; return this; }

        public FeatureVector build() {
            return new FeatureVector(
                    instrument, timestamp,
                    returnPct1m, volatility5m, volatility20m,
                    smaDistance, emaDistance, rsiValue,
                    macdValue, macdSignal, atrValue,
                    volumeRatio, highLowRatio, closePosition,
                    hourOfDay, dayOfWeek,
                    price, tickCount
            );
        }
    }
}

