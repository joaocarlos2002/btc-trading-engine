package dev.romeo.btctradingengine.adapter;


import dev.romeo.btctradingengine.model.NormalizedPriceEvent;

import java.math.BigDecimal;
import java.time.Instant;

public class FakeBinanceSource implements MarketDataSource {
    @Override
    public void start(PriceEventListener listener) {
        for (int i = 0; i < 100; i = i + 2) {
            String instrumentFake = "BTC/USD";
            BigDecimal price = new BigDecimal("100" + i);
                BigDecimal quantity = BigDecimal.ONE;
            Instant eventTimestamp = Instant.now();
            Instant receiptTimestamp = Instant.now();

                NormalizedPriceEvent event = new NormalizedPriceEvent(
                    instrumentFake, price, eventTimestamp, receiptTimestamp, quantity);
            listener.onEvent(event);
        }
    }

    @Override
    public void stop() {
        System.out.println("FakeBinanceSocket stop");
    }
}

