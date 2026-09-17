package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VpinEntryGuardTest {

    private final VpinEntryGuard guard = new VpinEntryGuard(true, new BigDecimal("0.35"));

    @Test
    public void blocksAtOrAboveTheThreshold() {
        assertTrue(guard.blocksEntry(features("0.35")));
        assertTrue(guard.blocksEntry(features("0.60")));
        assertFalse(guard.blocksEntry(features("0.20")));
    }

    @Test
    public void neverBlocksWhileVpinIsWarmingUp() {
        assertFalse(guard.blocksEntry(features("0")));
    }

    @Test
    public void disabledGuardNeverBlocks() {
        assertFalse(new VpinEntryGuard(false, new BigDecimal("0.35")).blocksEntry(features("0.90")));
        assertFalse(VpinEntryGuard.disabled().blocksEntry(features("1")));
    }

    private FeatureVector features(String vpin) {
        return FeatureVector.builder()
                .instrument("BTC/USD")
                .timestamp(Instant.now())
                .vpin(new BigDecimal(vpin))
                .price(new BigDecimal("100"))
                .tickCount(10)
                .build();
    }
}
