package dev.romeo.btctradingengine.model;

import java.math.BigDecimal;
import java.time.Instant;

public record NormalizedPriceEvent(
        String instrument,
        BigDecimal price,
        Instant eventTimestamp,
                Instant receiptTimestamp,
                BigDecimal quantity) {

        public NormalizedPriceEvent(
                        String instrument,
                        BigDecimal price,
                        Instant eventTimestamp,
                        Instant receiptTimestamp) {
                this(instrument, price, eventTimestamp, receiptTimestamp, BigDecimal.ZERO);
        }

}

