package dev.romeo.btctradingengine.prediction.rules;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.prediction.SignalRule;

import java.math.BigDecimal;

public class AtrRule implements SignalRule {

    @Override
    public double evaluate(FeatureVector features) {
        BigDecimal atr = features.atrValue();
        BigDecimal price = features.price();

        if (atr.compareTo(BigDecimal.ZERO) == 0 || price.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0; // Sem dados
        }

        // Calcular ATR em % do preÃ§o
        BigDecimal atrPercent = atr.divide(price, 8, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // ATR nÃ£o prediz direÃ§Ã£o, mas afeta confianÃ§a
        // ATR baixo: movimento baixo (confianÃ§a baixa nas regras)
        // ATR alto: movimento esperado maior (confianÃ§a mÃ©dia)

        if (atrPercent.compareTo(Config.getAtrVolatilityLow()) < 0) {
            // ATR < 2% do preÃ§o: baixÃ­ssima volatilidade
            // Desconfiar de sinais (pouco movimento esperado)
            return -0.3;
        } else if (atrPercent.compareTo(Config.getAtrVolatilityNormal()) < 0) {
            // 2-4%: volatilidade normal
            return 0.0;
        } else if (atrPercent.compareTo(Config.getAtrVolatilityHigh()) < 0) {
            // 4-6%: volatilidade moderada
            return 0.1;
        } else {
            // > 6%: volatilidade alta (possÃ­vel breakout)
            return 0.2;
        }
    }

    @Override
    public String getName() {
        return "ATR-Volatility";
    }
}

