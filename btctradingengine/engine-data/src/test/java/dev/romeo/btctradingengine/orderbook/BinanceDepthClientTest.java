package dev.romeo.btctradingengine.orderbook;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BinanceDepthClientTest {

    @Test
    public void sumsQuantitiesOfEachSideAndComputesTheBidShare() throws Exception {
        BinanceDepthClient.DepthSnapshot snapshot = BinanceDepthClient.parse(
                "{\"lastUpdateId\":1,"
                        + "\"bids\":[[\"77432.70000000\",\"1.81299000\"],[\"77432.60000000\",\"0.18701000\"]],"
                        + "\"asks\":[[\"77432.71000000\",\"0.50000000\"],[\"77432.80000000\",\"0.16666667\"]]}");

        assertEquals(0, snapshot.bidVolume().compareTo(new BigDecimal("2")));
        assertEquals(0, snapshot.askVolume().compareTo(new BigDecimal("0.66666667")));
        assertEquals(2, snapshot.levels());
        // 2 / 2.66666667
        assertEquals(0, snapshot.imbalance().compareTo(new BigDecimal("0.75000000")));
    }

    @Test
    public void emptyBookHasNoImbalance() throws Exception {
        BinanceDepthClient.DepthSnapshot snapshot = BinanceDepthClient.parse("{\"lastUpdateId\":1,\"bids\":[],\"asks\":[]}");

        assertNull(snapshot.imbalance());
    }
}
