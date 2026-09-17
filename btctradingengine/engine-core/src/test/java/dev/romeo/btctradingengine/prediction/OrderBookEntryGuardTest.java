package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OrderBookEntryGuardTest {

    private final OrderBookEntryGuard guard =
            new OrderBookEntryGuard(true, new BigDecimal("0.35"), new BigDecimal("0.65"));

    @Test
    public void blocksBuyingIntoAnAskHeavyBookAndSellingIntoABidHeavyOne() {
        assertNotNull(guard.blockReason(features("0.30"), Signal.BUY));
        assertNotNull(guard.blockReason(features("0.70"), Signal.SELL));
    }

    @Test
    public void onlyTheDirectionThatFightsTheBookIsBlocked() {
        // an ask-heavy book is no reason not to sell, and a bid-heavy one no reason not to buy
        assertNull(guard.blockReason(features("0.30"), Signal.SELL));
        assertNull(guard.blockReason(features("0.70"), Signal.BUY));
        assertNull(guard.blockReason(features("0.50"), Signal.BUY));
    }

    @Test
    public void neverBlocksWithoutDataOrWhenDisabled() {
        assertNull(guard.blockReason(features(null), Signal.BUY));
        assertNull(new OrderBookEntryGuard(false, new BigDecimal("0.35"), new BigDecimal("0.65"))
                .blockReason(features("0.10"), Signal.BUY));
    }

    @Test
    public void allOfReportsTheFirstGuardThatBlocks() {
        EntryGuard vpin = new VpinEntryGuard(true, new BigDecimal("0.35"));
        EntryGuard combined = EntryGuard.allOf(vpin, guard);

        FeatureVector toxicAndAskHeavy = FeatureVector.builder()
                .instrument("BTC/USD").timestamp(Instant.now())
                .vpin(new BigDecimal("0.5")).orderBookImbalance(new BigDecimal("0.2"))
                .price(new BigDecimal("100")).tickCount(10)
                .build();

        assertEquals("VPIN=0.5", combined.blockReason(toxicAndAskHeavy, Signal.BUY));
        assertNull(EntryGuard.allOf(EntryGuard.NONE).blockReason(toxicAndAskHeavy, Signal.BUY));
    }

    private FeatureVector features(String imbalance) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .orderBookImbalance(imbalance == null ? null : new BigDecimal(imbalance))
                .price(new BigDecimal("100"))
                .tickCount(10)
                .build();
    }
}
