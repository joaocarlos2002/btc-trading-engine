package dev.romeo.btctradingengine.prediction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PredictionVector(
        String instrument,
        Instant timestamp,

        Signal signal,              // BUY, SELL, HOLD
        BigDecimal probabilityUp,   // [0, 1] probabilidade implÃ­cita pelo score
        BigDecimal probabilityDown, // [0, 1] probabilidade implÃ­cita pelo score
        BigDecimal confidence,      // [0, 1] magnitude do score heurÃ­stico

        BigDecimal price,           // PreÃ§o no momento
        String modelVersion,        // "rules-v1", "rules-v2", etc
        MarketRegime marketRegime,  // regime the decision was made in (issue #7)
        boolean entryAllowed,       // false when filter rules or an entry guard (VPIN, order book) block NEW entries; exits still apply
        String reason               // ExplicaÃ§Ã£o legÃ­vel da decisÃ£o
) {

    public PredictionVector {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(probabilityUp, "probabilityUp");
        Objects.requireNonNull(probabilityDown, "probabilityDown");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(marketRegime, "marketRegime");
        Objects.requireNonNull(reason, "reason");

        validateUnitInterval("probabilityUp", probabilityUp);
        validateUnitInterval("probabilityDown", probabilityDown);
        validateUnitInterval("confidence", confidence);
        if (probabilityUp.add(probabilityDown).compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("probabilityUp and probabilityDown must sum to 1");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
    }

    private static void validateUnitInterval(String field, BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String instrument;
        private Instant timestamp;
        private Signal signal;
        private BigDecimal probabilityUp;
        private BigDecimal probabilityDown;
        private BigDecimal confidence;
        private BigDecimal price;
        private String modelVersion;
        private MarketRegime marketRegime = MarketRegime.UNKNOWN;
        private boolean entryAllowed = true;
        private String reason;

        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder signal(Signal signal) { this.signal = signal; return this; }
        public Builder probabilityUp(BigDecimal probabilityUp) { this.probabilityUp = probabilityUp; return this; }
        public Builder probabilityDown(BigDecimal probabilityDown) { this.probabilityDown = probabilityDown; return this; }
        public Builder confidence(BigDecimal confidence) { this.confidence = confidence; return this; }
        public Builder price(BigDecimal price) { this.price = price; return this; }
        public Builder modelVersion(String modelVersion) { this.modelVersion = modelVersion; return this; }
        public Builder marketRegime(MarketRegime marketRegime) { this.marketRegime = marketRegime; return this; }
        public Builder entryAllowed(boolean entryAllowed) { this.entryAllowed = entryAllowed; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }

        public PredictionVector build() {
            return new PredictionVector(
                    instrument, timestamp,
                    signal, probabilityUp, probabilityDown, confidence,
                    price, modelVersion, marketRegime, entryAllowed, reason
            );
        }
    }
}

