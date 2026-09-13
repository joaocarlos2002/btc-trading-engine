package dev.romeo.btctradingengine.derivatives;

import dev.romeo.btctradingengine.feature.DerivFeatures;
import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DerivativesHistoryTest {

    private static final Duration STALE = Duration.ofMinutes(5);
    private static final Duration OI_WINDOW = Duration.ofMinutes(60);

    @Test
    public void fundingIsOnlyVisibleFromItsSettlementTime() {
        DerivativesHistory history = new DerivativesHistory(STALE, OI_WINDOW, Duration.ZERO);
        history.addFundingRate(at("2026-09-08T08:00:00Z"), new BigDecimal("0.0001"));

        // the candle closing just before settlement must not see the rate - that would be lookahead
        assertNull(features(history, "2026-09-08T07:59:00Z").fundingRate());
        assertEquals(0, features(history, "2026-09-08T08:00:00Z").fundingRate().compareTo(new BigDecimal("0.0001")));
        // still the current rate until the next settlement (8h) plus the stale margin
        assertEquals(0, features(history, "2026-09-08T15:59:00Z").fundingRate().compareTo(new BigDecimal("0.0001")));
        assertNull(features(history, "2026-09-08T16:10:00Z").fundingRate());
    }

    @Test
    public void basisPairsThePerpetualKlineWithTheSameOpenTime() {
        DerivativesHistory history = new DerivativesHistory(STALE, OI_WINDOW, Duration.ZERO);
        history.addPerpClose(at("2026-09-08T10:00:00Z"), new BigDecimal("101"));

        // (101 - 100) / 100 * 100 = 1%
        assertEquals(0, features(history, "2026-09-08T10:00:00Z").basisPercent().compareTo(BigDecimal.ONE));
        // no perpetual kline for this minute: missing, not a zero basis
        assertNull(features(history, "2026-09-08T10:01:00Z").basisPercent());
    }

    @Test
    public void readingsAreNeitherSeenEarlyNorCarriedForwardWhenStale() {
        DerivativesHistory history = new DerivativesHistory(STALE, OI_WINDOW, Duration.ZERO);
        history.addOpenInterest(at("2026-09-08T10:00:30Z"), new BigDecimal("1000"));
        history.addLongShortRatio(at("2026-09-08T10:00:30Z"), new BigDecimal("1.8"));

        // candle closing 10:00:00 - before the reading was received
        assertNull(features(history, "2026-09-08T09:59:00Z").openInterest());
        DerivFeatures fresh = features(history, "2026-09-08T10:00:00Z");
        assertEquals(0, fresh.openInterest().compareTo(new BigDecimal("1000")));
        assertEquals(0, fresh.longShortRatio().compareTo(new BigDecimal("1.8")));
        // closing 10:07, the reading is 6.5 minutes old: older than the 5 minute stale limit
        DerivFeatures stale = features(history, "2026-09-08T10:06:00Z");
        assertNull(stale.openInterest());
        assertNull(stale.longShortRatio());
    }

    @Test
    public void openInterestChangeComparesAgainstTheWindowStart() {
        DerivativesHistory history = new DerivativesHistory(STALE, OI_WINDOW, Duration.ZERO);
        history.addOpenInterest(at("2026-09-08T09:00:30Z"), new BigDecimal("1000"));
        history.addOpenInterest(at("2026-09-08T10:00:30Z"), new BigDecimal("1100"));

        DerivFeatures features = features(history, "2026-09-08T10:00:00Z");
        assertEquals(0, features.openInterestChangePercent().compareTo(BigDecimal.TEN));

        // without a reading an hour earlier there is nothing to compare against
        DerivativesHistory young = new DerivativesHistory(STALE, OI_WINDOW, Duration.ZERO);
        young.addOpenInterest(at("2026-09-08T10:00:30Z"), new BigDecimal("1100"));
        assertNull(features(young, "2026-09-08T10:00:00Z").openInterestChangePercent());
    }

    @Test
    public void retentionDropsReadingsOlderThanTheWindow() {
        DerivativesHistory history = new DerivativesHistory(STALE, OI_WINDOW, Duration.ofHours(1));
        history.addOpenInterest(at("2026-09-08T08:00:00Z"), BigDecimal.ONE);
        history.addOpenInterest(at("2026-09-08T10:00:00Z"), BigDecimal.TWO);

        assertNull(features(history, "2026-09-08T08:00:00Z").openInterest());
    }

    @Test
    public void emptyHistoryHasNoData() {
        DerivativesHistory history = new DerivativesHistory(STALE, OI_WINDOW, Duration.ZERO);

        assertTrue(history.isEmpty());
        assertFalse(features(history, "2026-09-08T10:00:00Z").hasData());
    }

    private static Instant at(String timestamp) {
        return Instant.parse(timestamp);
    }

    /** A 1m spot candle opening at the given time and closing 59.999s later, like Binance klines. */
    private static DerivFeatures features(DerivativesHistory history, String openTime) {
        Instant open = at(openTime);
        CandleEvent candle = new CandleEvent(
                "BTCUSDT",
                open,
                open.plusMillis(59_999),
                new BigDecimal("100"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                new BigDecimal("100"),
                BigDecimal.ONE,
                1);
        return history.featuresFor(candle);
    }
}
