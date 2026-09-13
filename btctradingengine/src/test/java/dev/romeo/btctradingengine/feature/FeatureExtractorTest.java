package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.indicator.VwapAnchor;
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

    @Test
    public void populatesAllFeatureGroupsFromTheFirstCandle() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        extractor.onEvent(createCandle("100", "110", "95", "105", "1000", 50));

        FeatureVector fv = features.get(0);
        assertNotNull(fv.core());
        assertNotNull(fv.regime());
        assertNotNull(fv.context());
        assertNotNull(fv.flow());
        // Never null, so a future features.deriv().openInterest() cannot NPE (issue #9)
        assertNotNull(fv.deriv());
        assertFalse(fv.deriv().hasData());
    }

    @Test
    public void regimeAndContextStartAtWarmupDefaults() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(50, 26, 14, features::add);

        extractor.onEvent(createCandle("100", "110", "95", "105", "1000", 50));

        FeatureVector fv = features.get(0);
        // ADX/Bollinger/Donchian need more than one candle: zero means "no value yet"
        assertEquals(0, fv.regime().adx().compareTo(BigDecimal.ZERO));
        assertEquals(0, fv.regime().bbWidth().compareTo(BigDecimal.ZERO));
        assertEquals(0, fv.context().donchianUpper().compareTo(BigDecimal.ZERO));
        // MFI reports the neutral 50 during warmup, like rsiValue, so MfiRule stays neutral
        assertEquals(0, fv.flow().mfi().compareTo(new BigDecimal("50")));
        // The daily VWAP has no warmup: typical price of (110+95+105)/3
        assertEquals(0, fv.context().vwap().compareTo(new BigDecimal("103.33333333")));
    }

    @Test
    public void computesAtrPercentAsARegimeFeature() {
        List<FeatureVector> features = new ArrayList<>();
        FeatureExtractor extractor = new FeatureExtractor(
                new IndicatorPeriods(2, 2, 2, 1, 1, 2, 2, 2, 2, 2,
                        2, 2, new BigDecimal("2.0"), 2, 2, VwapAnchor.DAILY, 2),
                features::add);

        // ATR(1) is ready after one candle: true range = 110 - 95 = 15, close = 105
        extractor.onEvent(createCandle("100", "110", "95", "105", "1000", 50));

        FeatureVector fv = features.get(0);
        assertEquals(0, fv.atrValue().compareTo(new BigDecimal("15")));
        // 15 / 105 * 100 = 14.2857...
        assertEquals(new BigDecimal("14.29"),
                fv.regime().atrPercent().setScale(2, java.math.RoundingMode.HALF_EVEN));
    }

    @Test
    public void fillsRegimeAndContextOnceTheIndicatorsWarmUp() {
        List<FeatureVector> features = new ArrayList<>();
        // Small periods so everything is ready within a handful of candles
        FeatureExtractor extractor = new FeatureExtractor(
                new IndicatorPeriods(2, 2, 2, 2, 1, 2, 2, 2, 2, 2,
                        2, 2, new BigDecimal("2.0"), 2, 2, VwapAnchor.DAILY, 2),
                features::add);

        extractor.onEvent(createCandle("100", "110", "95", "105", "1000", 50));
        extractor.onEvent(createCandle("105", "120", "104", "118", "1200", 50));
        extractor.onEvent(createCandle("118", "130", "117", "128", "1400", 50));
        extractor.onEvent(createCandle("128", "140", "127", "138", "1600", 50));
        extractor.onEvent(createCandle("138", "150", "137", "148", "1800", 50));

        FeatureVector fv = features.get(features.size() - 1);
        assertTrue(fv.regime().adx().compareTo(BigDecimal.ZERO) > 0, "ADX should be populated");
        assertTrue(fv.regime().plusDi().compareTo(BigDecimal.ZERO) > 0, "+DI should be populated");
        assertTrue(fv.regime().bbWidth().compareTo(BigDecimal.ZERO) > 0, "BB width should be populated");
        assertTrue(fv.context().bbPercentB().compareTo(BigDecimal.ZERO) > 0, "%B should be populated");
        assertTrue(fv.context().donchianUpper().compareTo(fv.context().donchianLower()) > 0);
        assertTrue(fv.context().donchianPosition().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(fv.context().donchianPosition().compareTo(BigDecimal.ONE) <= 0);
        // Rising market: close is above the session VWAP
        assertTrue(fv.context().vwapDistance().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(fv.flow().mfi().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    public void warmUpFeedsTheSameIndicatorsAsOnEvent() {
        List<FeatureVector> live = new ArrayList<>();
        FeatureExtractor liveExtractor = new FeatureExtractor(50, 26, 14, live::add);
        List<FeatureVector> warmed = new ArrayList<>();
        FeatureExtractor warmupExtractor = new FeatureExtractor(50, 26, 14, features -> { });

        CandleEvent first = createCandle("100", "110", "95", "105", "1000", 50);
        CandleEvent second = createCandle("105", "120", "104", "118", "1200", 50);

        liveExtractor.onEvent(first);
        liveExtractor.onEvent(second);
        warmupExtractor.warmUp(first, warmed::add);
        warmupExtractor.warmUp(second, warmed::add);

        // Both paths go through the same process(), so the resulting vectors must match
        assertEquals(live.get(1).regime(), warmed.get(1).regime());
        assertEquals(live.get(1).context(), warmed.get(1).context());
        assertEquals(live.get(1).flow(), warmed.get(1).flow());
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


