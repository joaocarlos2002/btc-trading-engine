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
    // Regras de filtro (ex: ATR, Volatility) nao entram na media direcional -
    // sua faixa de score e muito menor que a das regras direcionais (RSI/SMA/MACD),
    // entao inclui-las na mesma media tornava o threshold de entrada matematicamente
    // inatingivel (o teto teorico da media ficava abaixo do proprio threshold).
    // Em vez disso, atuam como veto: score medio negativo bloqueia a entrada.
    private final List<SignalRule> filterRules;
    private final PredictionEventListener listener;
    private final double buyThreshold;
    private final double sellThreshold;
    private final int confirmationSnapshots;
    private final MarketRegimeClassifier regimeClassifier;
    private final boolean regimeGatingEnabled;
    private final EntryGuard entryGuard;

    // Estado para confirmaÃ§Ã£o temporal
    private Signal lastSignal = Signal.HOLD;
    private int signalConfirmationCount = 0;

    public RuleBasedPredictor(PredictionEventListener listener) {
        this(listener, Config.getBuyThreshold(), Config.getSellThreshold(), Config.getConfirmationSnapshots(),
                MarketRegimeClassifier.fromConfig(), Config.isRegimeGatingEnabled(),
                EntryGuard.allOf(VpinEntryGuard.fromConfig(), OrderBookEntryGuard.fromConfig()));
    }

    /** Allows overriding the entry threshold/confirmation without touching global Config - used by on-demand backtests. */
    public RuleBasedPredictor(PredictionEventListener listener, double buyThreshold, double sellThreshold, int confirmationSnapshots) {
        this(listener, buyThreshold, sellThreshold, confirmationSnapshots, MarketRegimeClassifier.fromConfig(), false,
                EntryGuard.NONE);
    }

    /**
     * The regime is always classified and reported; regimeGatingEnabled decides whether it also
     * removes the rule family the regime invalidates from the score (issue #7).
     */
    public RuleBasedPredictor(PredictionEventListener listener, double buyThreshold, double sellThreshold,
                              int confirmationSnapshots, MarketRegimeClassifier regimeClassifier,
                              boolean regimeGatingEnabled) {
        this(listener, buyThreshold, sellThreshold, confirmationSnapshots, regimeClassifier, regimeGatingEnabled,
                EntryGuard.NONE);
    }

    /**
     * entryGuard (VPIN, order book) marks the prediction as "no new entries" instead of turning it into
     * HOLD: the same prediction also closes an opposite position on signal reversal, and a bad moment
     * to enter is exactly when that exit must still go through (issues #13 and #10).
     */
    public RuleBasedPredictor(PredictionEventListener listener, double buyThreshold, double sellThreshold,
                              int confirmationSnapshots, MarketRegimeClassifier regimeClassifier,
                              boolean regimeGatingEnabled, EntryGuard entryGuard) {
        if (confirmationSnapshots < 1) {
            throw new IllegalArgumentException("confirmationSnapshots must be at least 1: " + confirmationSnapshots);
        }
        this.entryGuard = entryGuard;
        this.regimeClassifier = regimeClassifier;
        this.regimeGatingEnabled = regimeGatingEnabled;
        this.rules = new ArrayList<>();
        this.filterRules = new ArrayList<>();
        this.listener = listener;
        this.buyThreshold = buyThreshold;
        this.sellThreshold = sellThreshold;
        this.confirmationSnapshots = confirmationSnapshots;
    }

    public void addRule(SignalRule rule) {
        rules.add(rule);
        logger.debug("Added rule: {}", rule.getName());
    }

    public void addFilterRule(SignalRule rule) {
        filterRules.add(rule);
        logger.debug("Added filter rule: {}", rule.getName());
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

        MarketRegime regime = regimeClassifier.classify(features);
        double sumScore = 0.0;
        int votingRules = 0;
        StringBuilder reasonBuilder = new StringBuilder();
        reasonBuilder.append("regime=").append(regime).append("; ");

        for (SignalRule rule : rules) {
            if (regimeGatingEnabled && !regime.allows(rule.family())) {
                reasonBuilder.append(String.format("%s=off; ", rule.getName()));
                continue;
            }
            double score = rule.evaluate(features);
            sumScore += score;
            votingRules++;
            reasonBuilder.append(String.format("%s=%.2f; ", rule.getName(), score));
        }

        // Average over the rules that voted: a gated-out family must not dilute the one left active
        double avgScore = votingRules == 0 ? 0.0 : sumScore / votingRules;
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
                    candidateSignal, signalConfirmationCount, confirmationSnapshots);
        }

        // A filter veto blocks NEW entries only, exactly like the entry guards. It used to turn the signal
        // into HOLD, which also swallowed the reversal that closes an opposite position - a position
        // opened before conditions turned bad then stayed open until target or stop.
        // Guards are directional (the order book one is), so there is only something to judge on BUY/SELL.
        String entryBlockReason = null;
        if (finalSignal != Signal.HOLD) {
            entryBlockReason = isVetoedByFilters(features, reasonBuilder)
                    ? "filter rules"
                    : entryGuard.blockReason(features, finalSignal);
        }
        boolean entryAllowed = entryBlockReason == null;
        if (!entryAllowed) {
            confirmationStatus += " [new entries blocked: " + entryBlockReason + "]";
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
                .marketRegime(regime)
                .entryAllowed(entryAllowed)
                .reason(reasonBuilder.toString() + confirmationStatus)
                .build();
    }

    private boolean isVetoedByFilters(FeatureVector features, StringBuilder reasonBuilder) {
        if (filterRules.isEmpty()) {
            return false;
        }

        double filterSum = 0.0;
        for (SignalRule rule : filterRules) {
            double score = rule.evaluate(features);
            filterSum += score;
            reasonBuilder.append(String.format("%s=%.2f; ", rule.getName(), score));
        }

        double filterAvg = filterSum / filterRules.size();
        reasonBuilder.append(String.format("filterAvg=%.3f; ", filterAvg));
        return filterAvg < 0;
    }

    /**
     * Emits BUY/SELL only after the same candidate was seen confirmationSnapshots candles in a row.
     * The counter used to start at 1 on a change and still return HOLD there, so N=1 needed two
     * candles (issue #88); now each BUY/SELL candle counts once and N=1 passes straight through.
     */
    private Signal applyTemporalConfirmation(Signal candidateSignal) {
        if (candidateSignal != lastSignal) {
            lastSignal = candidateSignal;
            signalConfirmationCount = 0;
        }

        if (candidateSignal == Signal.HOLD) {
            signalConfirmationCount = 0;
            return Signal.HOLD;
        }

        signalConfirmationCount++;
        if (signalConfirmationCount >= confirmationSnapshots) {
            if (signalConfirmationCount == confirmationSnapshots) {
                logger.info("Signal CONFIRMED: {} after {} snapshots", candidateSignal, signalConfirmationCount);
            }
            return candidateSignal;
        }
        logger.debug("Signal confirmation: {} ({}/{})", candidateSignal, signalConfirmationCount, confirmationSnapshots);
        return Signal.HOLD;
    }

    private Signal scoreToSignal(double avgScore) {
        // Hysteresis: thresholds mais altos para evitar oscilaÃ§Ã£o
        // BUY: score > 0.65
        // SELL: score < -0.65
        // HOLD: tudo no meio

        if (avgScore >= buyThreshold) {
            return Signal.BUY;
        } else if (avgScore <= sellThreshold) {
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

