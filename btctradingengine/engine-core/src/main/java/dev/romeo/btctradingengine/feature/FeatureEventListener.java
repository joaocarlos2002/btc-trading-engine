package dev.romeo.btctradingengine.feature;

@FunctionalInterface
public interface FeatureEventListener {
    void onEvent(FeatureVector features);
}

