package dev.romeo.btctradingengine.prediction;

import dev.romeo.btctradingengine.feature.FeatureVector;
import dev.romeo.btctradingengine.feature.FeatureEventListener;
import dev.romeo.btctradingengine.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class RuleBasedPredictor implements FeatureEventListener {
    private static final Logger logger = LoggerFactory.getLogger(RuleBasedPredictor.class);
    private static final String MODEL_VERSION = "rules-v1-score-sigmoid";

    // Hysteresis thresholds - Ã©vita oscilaÃ§Ã£o
    // Temporal confirmation - evita entrada em um Ãºnico snapshot
    private final List<SignalRule> rules;
    private final PredictionEventListener listener;

    // Estado para confirmaÃ§Ã£o temporal
    private Signal lastSignal = Signal.HOLD;
    private int signalConfirmationCount = 0;

    public RuleBasedPredictor(PredictionEventListener listener) {
        this.rules = new ArrayList<>();
        this.listener = listener;
    }

    public void addRule(SignalRule rule) {
        rules.add(rule);
        logger.debug("Added rule: {}", rule.getName());
    }

    @Override
    public void onEvent(FeatureVector features) {
        try {
            PredictionVector prediction = predict(features);
            listener.onEvent(prediction);
        } catch (Exception e) {
            logger.error("Error making prediction", e);
        }
    }

    private PredictionVector predict(FeatureVector features) {
        if (rules.isEmpty()) {
            return defaultPrediction(features);
        }

        double sumScore = 0.0;
        StringBuilder reasonBuilder = new StringBuilder();

        for (SignalRule rule : rules) {
            double score = rule.evaluate(features);
            sumScore += score;
            reasonBuilder.append(String.format("%s=%.2f; ", rule.getName(), score));
        }

        double avgScore = sumScore / rules.size();
        reasonBuilder.append(String.format("sum=%.2f; avg=%.3f; ", sumScore, avgScore));
        Signal candidateSignal = scoreToSignal(avgScore);
        BigDecimal confidence = calculateConfidence(avgScore);
        BigDecimal probabilityUp = calculateProbabilityUp(avgScore);
        BigDecimal probabilityDown = BigDecimal.ONE.subtract(probabilityUp)
            .setScale(3, RoundingMode.HALF_UP);

        // Temporal confirmation: sÃ³ aceita entrada (BUY/SELL) com confirmaÃ§Ã£o
        Signal finalSignal = applyTemporalConfirmation(candidateSignal);

        String confirmationStatus = "";
        if (finalSignal != candidateSignal) {
            confirmationStatus = String.format(" [candidate=%s, need=%d/%d confirms]",
                    candidateSignal, signalConfirmationCount, Config.getConfirmationSnapshots());
        }

        return PredictionVector.builder()
                .instrument(features.instrument())
                .timestamp(features.timestamp())
                .signal(finalSignal)
                .probabilityUp(probabilityUp)
                .probabilityDown(probabilityDown)
                .confidence(confidence)
                .price(features.price())
                .modelVersion(MODEL_VERSION)
                .reason(reasonBuilder.toString() + confirmationStatus)
                .build();
    }

    private Signal applyTemporalConfirmation(Signal candidateSignal) {
        // Se o sinal mudou, reseta o contador de confirmaÃ§Ã£o
        if (candidateSignal != lastSignal) {
            lastSignal = candidateSignal;
            signalConfirmationCount = 1;

            // Se Ã© HOLD, aceita imediatamente
            if (candidateSignal == Signal.HOLD) {
                return Signal.HOLD;
            }

            // BUY/SELL candidato: precisa de confirmaÃ§Ã£o
            logger.debug("Signal candidate: {} (1/{} confirms)", candidateSignal, Config.getConfirmationSnapshots());
            return Signal.HOLD; // Retorna HOLD atÃ© ter confirmaÃ§Ãµes
        }

        // Mesmo sinal, incrementa contador
        if (candidateSignal != Signal.HOLD) {
            signalConfirmationCount++;
            logger.debug("Signal confirmation: {} ({}/{})", candidateSignal, signalConfirmationCount, Config.getConfirmationSnapshots());

            if (signalConfirmationCount >= Config.getConfirmationSnapshots()) {
                logger.info("âœ“ Signal CONFIRMED: {} after {} snapshots", candidateSignal, signalConfirmationCount);
                return candidateSignal;
            }
            return Signal.HOLD; // Ainda aguardando confirmaÃ§Ã£o
        }

        // HOLD continua sendo HOLD
        signalConfirmationCount = 0;
        return Signal.HOLD;
    }

    private Signal scoreToSignal(double avgScore) {
        // Hysteresis: thresholds mais altos para evitar oscilaÃ§Ã£o
        // BUY: score > 0.65
        // SELL: score < -0.65
        // HOLD: tudo no meio

        if (avgScore >= Config.getBuyThreshold()) {
            return Signal.BUY;
        } else if (avgScore <= Config.getSellThreshold()) {
            return Signal.SELL;
        } else if (avgScore > Config.getHoldMax()) {
            // Score positivo mas abaixo de BUY_THRESHOLD â†’ lean BUY (but hold)
            return Signal.HOLD;
        } else if (avgScore < Config.getHoldMin()) {
            // Score negativo mas acima de SELL_THRESHOLD â†’ lean SELL (but hold)
            return Signal.HOLD;
        } else {
            // Score prÃ³ximo de 0 â†’ neutro
            return Signal.HOLD;
        }
    }

    private BigDecimal calculateConfidence(double avgScore) {
        double absScore = Math.abs(avgScore);
        double confidence = Math.min(absScore, 1.0);
        return BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateProbabilityUp(double avgScore) {
        double sigmoid = 1.0 / (1.0 + Math.exp(-avgScore * 2));
        return BigDecimal.valueOf(sigmoid).setScale(3, RoundingMode.HALF_UP);
    }

    private PredictionVector defaultPrediction(FeatureVector features) {
        return PredictionVector.builder()
                .instrument(features.instrument())
                .timestamp(features.timestamp())
                .signal(Signal.HOLD)
                .probabilityUp(BigDecimal.valueOf(0.5))
                .probabilityDown(BigDecimal.valueOf(0.5))
                .confidence(BigDecimal.ZERO)
                .price(features.price())
                .modelVersion(MODEL_VERSION)
                .reason("No rules configured")
                .build();
    }
}

