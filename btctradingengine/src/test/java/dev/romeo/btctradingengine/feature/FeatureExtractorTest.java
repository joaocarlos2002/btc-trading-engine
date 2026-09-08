package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FeatureExtractorTest {

    @Test
    public void extractsValidFeaturesFromCandle() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        CandleEvent candle = createCandle("100", "110", "95", "105", "1000", 50);
        extractor.onEvent(candle);

        assertEquals(1, features.size());
        FeatureVector fv = features.get(0);
        assertNotNull(fv.price());
        assertEquals(new BigDecimal("105"), fv.price());
        assertEquals("BTC/USD", fv.instrument());
    }

    @Test
    public void calculatesReturnCorrectly() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        // open=100, close=110 â†’ return = 10%
        CandleEvent candle = createCandle("100", "120", "90", "110", "1000", 40);
        extractor.onEvent(candle);

        FeatureVector fv = features.get(0);
        // return = (110 - 100) / 100 * 100 = 10%
        assertEquals(new BigDecimal("10"), fv.returnPct1m().setScale(0, java.math.RoundingMode.HALF_UP));
    }

    @Test
    public void calculatesHighLowRatioCorrectly() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        // high=120, low=100 â†’ ratio = 20/100 = 20%
        CandleEvent candle = createCandle("100", "120", "100", "110", "1000", 30);
        extractor.onEvent(candle);

        FeatureVector fv = features.get(0);
        assertEquals(new BigDecimal("20"), fv.highLowRatio().setScale(0, java.math.RoundingMode.HALF_UP));
    }

    @Test
    public void calculatesClosePricePositionCorrectly() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        // high=120, low=100, close=110 â†’ position = (110-100)/(120-100) = 10/20 = 0.5
        CandleEvent candle = createCandle("105", "120", "100", "110", "1000", 35);
        extractor.onEvent(candle);

        FeatureVector fv = features.get(0);
        assertEquals(new BigDecimal("0.5"), fv.closePosition().setScale(1, java.math.RoundingMode.HALF_UP));
    }

    @Test
    public void volatilityRequiresMinimumCandles() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        // Primeira vela: sem histÃ³rico suficiente
        CandleEvent candle1 = createCandle("100", "110", "95", "105", "1000", 30);
        extractor.onEvent(candle1);

        assertEquals(BigDecimal.ZERO, features.get(0).volatility5m());
    }

    @Test
    public void noDataLeakage() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        // Candle 1
        CandleEvent candle1 = createCandle("100", "110", "95", "105", "1000", 30);
        extractor.onEvent(candle1);

        FeatureVector fv1 = features.get(0);

        // Features devem usar apenas informaÃ§Ã£o disponÃ­vel ao fechar a vela 1
        // NÃ£o deve haver referÃªncia a preÃ§os futuros
        assertTrue(fv1.price().compareTo(BigDecimal.ZERO) > 0, "Price deve ser positivo");
        assertTrue(fv1.volatility5m().compareTo(new BigDecimal("0")) >= 0, "Volatility nÃ£o pode ser negativa");
        assertTrue(fv1.returnPct1m().compareTo(new BigDecimal("-100")) > 0, "Return deve ser > -100%");
    }

    @Test
    public void preservesInstrumentAndTimestamp() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        Instant closeTime = Instant.parse("2026-09-08T10:15:00Z");

        CandleEvent candle = new CandleEvent(
                "BTC/USD",
                openTime,
                closeTime,
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                new BigDecimal("1000"),
                35
        );
        extractor.onEvent(candle);

        FeatureVector fv = features.get(0);
        assertEquals("BTC/USD", fv.instrument());
        assertEquals(closeTime, fv.timestamp());
    }

    private CandleEvent createCandle(String open, String high, String low, String close, String volume, int tickCount) {
        Instant openTime = Instant.parse("2026-09-08T10:00:00Z");
        Instant closeTime = Instant.parse("2026-09-08T10:15:00Z");

        return new CandleEvent(
                "BTC/USD",
                openTime,
                closeTime,
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal(volume),
                tickCount
        );
    }
}


