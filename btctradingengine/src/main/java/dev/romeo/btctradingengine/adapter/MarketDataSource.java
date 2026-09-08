package dev.romeo.btctradingengine.adapter;

public interface MarketDataSource {
    void start(PriceEventListener listener);

    void stop();
}

