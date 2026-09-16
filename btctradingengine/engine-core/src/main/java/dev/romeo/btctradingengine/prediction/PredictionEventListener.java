package dev.romeo.btctradingengine.prediction;

@FunctionalInterface
public interface PredictionEventListener {
    void onEvent(PredictionVector prediction);
}

