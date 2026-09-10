package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class MacdRule implements SignalRule {
    private final BigDecimal strongHistogramAtrRatio;

    public MacdRule() {
        this(Config.getMacdStrongHistogramAtrRatio());
    }

    /** Allows overriding the threshold without touching global Config - used by on-demand backtests. */
    public MacdRule(BigDecimal strongHistogramAtrRatio) {
        this.strongHistogramAtrRatio = strongHistogramAtrRatio;
    }

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal macd = features.macdValue();
        BigDecimal signal = features.macdSignal();

        if (macd.compareTo(BigDecimal.ZERO) == 0 || signal.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0; // Sem histÃ³rico suficiente
        }

        BigDecimal histogram = macd.subtract(signal);
        BigDecimal atr = features.atrValue();

        if (atr.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0; // Sem ATR para normalizar a forÃ§a do histograma
        }

        BigDecimal histogramAtrRatio = histogram.abs()
                .divide(atr, 8, java.math.RoundingMode.HALF_UP);

        if (histogram.compareTo(BigDecimal.ZERO) > 0) {
            // MACD acima do sinal: bullish
            // A forÃ§a Ã© relativa ao ATR do candle.
            if (histogramAtrRatio.compareTo(strongHistogramAtrRatio) > 0) {
                return 0.7;  // Forte bullish
            } else {
                return 0.3;  // Fraco bullish
            }
        } else {
            // MACD abaixo do sinal: bearish
            if (histogramAtrRatio.compareTo(strongHistogramAtrRatio) > 0) {
                return -0.7; // Forte bearish
            } else {
                return -0.3; // Fraco bearish
            }
        }
    }

    @Override
    public String getName() {
        return "MACD";
    }
}

