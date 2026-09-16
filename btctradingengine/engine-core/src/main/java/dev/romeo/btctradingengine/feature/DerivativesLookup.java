package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;

/**
 * Supplies the derivatives readings that belong to a spot candle. Derivatives do not come out of the
 * candle stream (they are polled from the futures API), so FeatureExtractor looks them up per candle.
 */
@FunctionalInterface
public interface DerivativesLookup {

    DerivativesLookup NONE = candle -> DerivFeatures.empty();

    DerivFeatures featuresFor(CandleEvent candle);
}
