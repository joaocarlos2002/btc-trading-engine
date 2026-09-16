package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #95 made Rsi, MfiIndicator, BollingerBands, DonchianChannel and PriceAction incremental.
 * This pins them to the original full-window implementations, kept below as reference classes,
 * candle by candle including warmup. BigDecimal arithmetic stays exact, so the comparison is exact
 * too: {@code equals}, i.e. same value AND same scale, not a tolerance.
 */
public class IndicatorEquivalenceTest {

    private static final int CANDLES = 4000;
    private static final int[] PERIODS = {1, 2, 3, 14, 50, 210, 300};

    @Test
    public void rsiMatchesFullWindowSum() {
        for (long seed = 1; seed <= 3; seed++) {
            List<CandleEvent> candles = candles(seed);
            for (int period : PERIODS) {
                ReferenceRsi reference = new ReferenceRsi(period);
                Rsi rsi = new Rsi(period);
                for (int i = 0; i < candles.size(); i++) {
                    BigDecimal close = candles.get(i).close();
                    assertEquals(reference.update(close), rsi.update(close), where(seed, period, i));
                }
            }
        }
    }

    @Test
    public void mfiMatchesFullWindowSum() {
        for (long seed = 1; seed <= 3; seed++) {
            List<CandleEvent> candles = candles(seed);
            for (int period : PERIODS) {
                ReferenceMfi reference = new ReferenceMfi(period);
                MfiIndicator mfi = new MfiIndicator(period);
                for (int i = 0; i < candles.size(); i++) {
                    CandleEvent candle = candles.get(i);
                    assertEquals(reference.update(candle), mfi.update(candle), where(seed, period, i));
                }
            }
        }
    }

    @Test
    public void bollingerMatchesFullWindowVariance() {
        for (long seed = 1; seed <= 3; seed++) {
            List<CandleEvent> candles = candles(seed);
            for (int period : PERIODS) {
                for (BigDecimal multiplier : new BigDecimal[] {new BigDecimal("2.0"), new BigDecimal("1.5")}) {
                    ReferenceBollinger reference = new ReferenceBollinger(period, multiplier);
                    BollingerBands bollinger = new BollingerBands(period, multiplier);
                    for (int i = 0; i < candles.size(); i++) {
                        BigDecimal close = candles.get(i).close();
                        assertEquals(reference.update(close), bollinger.update(close), where(seed, period, i));
                    }
                }
            }
        }
    }

    @Test
    public void donchianMatchesFullWindowScan() {
        for (long seed = 1; seed <= 3; seed++) {
            List<CandleEvent> candles = candles(seed);
            for (int period : PERIODS) {
                ReferenceDonchian reference = new ReferenceDonchian(period);
                DonchianChannel donchian = new DonchianChannel(period);
                for (int i = 0; i < candles.size(); i++) {
                    CandleEvent candle = candles.get(i);
                    assertEquals(reference.update(candle), donchian.update(candle), where(seed, period, i));
                }
            }
        }
    }

    @Test
    public void priceActionMatchesFullWindowScan() {
        int[][] settings = {{1, 1}, {2, 1}, {5, 2}, {20, 3}, {60, 5}, {300, 30}};
        for (long seed = 1; seed <= 3; seed++) {
            List<CandleEvent> candles = candles(seed);
            for (int[] setting : settings) {
                ReferencePriceAction reference = new ReferencePriceAction(setting[0], setting[1]);
                PriceAction priceAction = new PriceAction(setting[0], setting[1]);
                for (int i = 0; i < candles.size(); i++) {
                    CandleEvent candle = candles.get(i);
                    assertEquals(reference.update(candle), priceAction.update(candle),
                            where(seed, setting[0], i) + " swing=" + setting[1]);
                }
            }
        }
    }

    private static String where(long seed, int period, int index) {
        return "seed=" + seed + " period=" + period + " candle=" + index;
    }

    /**
     * Seeded random walk with the awkward cases mixed in: flat stretches (every OHLC equal, repeated),
     * zero-volume candles, prices snapped to a coarse grid so equal highs/lows are frequent, and the
     * same value written with different scales (100.5 vs 100.50) so tie-breaking shows in the output.
     */
    static List<CandleEvent> candles(long seed) {
        Random random = new Random(seed);
        List<CandleEvent> candles = new ArrayList<>(CANDLES);
        double price = 100;
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        int flatLeft = 0;
        for (int i = 0; i < CANDLES; i++) {
            if (flatLeft == 0 && random.nextInt(100) < 3) {
                flatLeft = 1 + random.nextInt(400);
            }
            BigDecimal open;
            BigDecimal high;
            BigDecimal low;
            BigDecimal close;
            if (flatLeft > 0) {
                flatLeft--;
                open = high = low = close = price(price, random);
            } else {
                boolean coarse = random.nextInt(4) == 0;
                double step = coarse ? Math.rint(random.nextGaussian() * 2) / 2 : random.nextGaussian() * 0.8;
                double o = price;
                double c = Math.max(1, o + step);
                double h = Math.max(o, c) + (coarse ? Math.rint(random.nextDouble() * 2) / 2 : random.nextDouble() * 0.5);
                double l = Math.max(0.5, Math.min(o, c) - (coarse ? Math.rint(random.nextDouble() * 2) / 2 : random.nextDouble() * 0.5));
                price = c;
                open = price(o, random);
                high = price(h, random);
                low = price(l, random);
                close = price(c, random);
            }
            BigDecimal volume = random.nextInt(10) == 0
                    ? (random.nextBoolean() ? BigDecimal.ZERO : new BigDecimal("0.000"))
                    : BigDecimal.valueOf(random.nextDouble() * 20).setScale(5, RoundingMode.HALF_EVEN);
            candles.add(new CandleEvent("BTCUSDT", t, t.plusSeconds(60), open, high, low, close, volume, 10));
            t = t.plusSeconds(60);
        }
        return candles;
    }

    private static BigDecimal price(double value, Random random) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN);
        return random.nextInt(5) == 0 ? rounded.setScale(3, RoundingMode.UNNECESSARY) : rounded;
    }

    // ---- Reference implementations: verbatim logic of the pre-#95 classes ----

    private static final class ReferenceRsi {
        private final int n;
        private BigDecimal previousPrice;
        private boolean seeded = false;
        private final Deque<BigDecimal> dts = new ArrayDeque<>();

        ReferenceRsi(int n) {
            this.n = n;
        }

        Optional<BigDecimal> update(BigDecimal p) {
            if (!seeded) {
                seeded = true;
                previousPrice = p;
                return Optional.empty();
            } else {
                BigDecimal delta = p.subtract(previousPrice);
                previousPrice = p;
                dts.addLast(delta);
                if (dts.size() > n) dts.removeFirst();
                if (dts.size() < n) return Optional.empty();
            }
            BigDecimal sumGain = BigDecimal.ZERO;
            BigDecimal sumLoss = BigDecimal.ZERO;
            for (BigDecimal val : dts) {
                if (val.compareTo(BigDecimal.ZERO) >= 0) {
                    sumGain = sumGain.add(val);
                } else {
                    sumLoss = sumLoss.add(val.abs());
                }
            }
            BigDecimal gain = sumGain.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);
            BigDecimal loss = sumLoss.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);
            if (loss.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.of(BigDecimal.valueOf(100));
            }
            BigDecimal rs = gain.divide(loss, 8, RoundingMode.HALF_EVEN);
            BigDecimal rsi = BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                    .divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_EVEN));
            return Optional.of(rsi);
        }
    }

    private static final class ReferenceMfi {
        private final int period;
        private final Deque<BigDecimal> positiveFlows = new ArrayDeque<>();
        private final Deque<BigDecimal> negativeFlows = new ArrayDeque<>();
        private BigDecimal previousTypicalPrice;
        private boolean seeded = false;

        ReferenceMfi(int period) {
            this.period = period;
        }

        Optional<BigDecimal> update(CandleEvent candle) {
            BigDecimal typicalPrice = candle.high().add(candle.low()).add(candle.close())
                    .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_EVEN);
            if (!seeded) {
                seeded = true;
                previousTypicalPrice = typicalPrice;
                return Optional.empty();
            }
            BigDecimal rawMoneyFlow = typicalPrice.multiply(candle.volume());
            int direction = typicalPrice.compareTo(previousTypicalPrice);
            previousTypicalPrice = typicalPrice;
            positiveFlows.addLast(direction > 0 ? rawMoneyFlow : BigDecimal.ZERO);
            negativeFlows.addLast(direction < 0 ? rawMoneyFlow : BigDecimal.ZERO);
            if (positiveFlows.size() > period) {
                positiveFlows.removeFirst();
                negativeFlows.removeFirst();
            }
            if (positiveFlows.size() < period) {
                return Optional.empty();
            }
            BigDecimal positiveSum = sum(positiveFlows);
            BigDecimal negativeSum = sum(negativeFlows);
            if (negativeSum.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.of(positiveSum.compareTo(BigDecimal.ZERO) > 0
                        ? BigDecimal.valueOf(100)
                        : BigDecimal.valueOf(50));
            }
            BigDecimal moneyRatio = positiveSum.divide(negativeSum, 8, RoundingMode.HALF_EVEN);
            return Optional.of(BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100)
                    .divide(BigDecimal.ONE.add(moneyRatio), 8, RoundingMode.HALF_EVEN)));
        }

        private static BigDecimal sum(Deque<BigDecimal> flows) {
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal flow : flows) {
                total = total.add(flow);
            }
            return total;
        }
    }

    private static final class ReferenceBollinger {
        private static final MathContext SQRT_CONTEXT = new MathContext(16, RoundingMode.HALF_EVEN);
        private final int period;
        private final BigDecimal stdDevMultiplier;
        private final Deque<BigDecimal> closes = new ArrayDeque<>();
        private BigDecimal runningSum = BigDecimal.ZERO;

        ReferenceBollinger(int period, BigDecimal stdDevMultiplier) {
            this.period = period;
            this.stdDevMultiplier = stdDevMultiplier;
        }

        Optional<BollingerBands.BollingerValue> update(BigDecimal close) {
            runningSum = runningSum.add(close);
            closes.addLast(close);
            if (closes.size() > period) {
                runningSum = runningSum.subtract(closes.removeFirst());
            }
            if (closes.size() < period) {
                return Optional.empty();
            }
            BigDecimal middle = runningSum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_EVEN);
            BigDecimal sumOfSquares = BigDecimal.ZERO;
            for (BigDecimal c : closes) {
                sumOfSquares = sumOfSquares.add(c.subtract(middle).pow(2));
            }
            BigDecimal variance = sumOfSquares.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_EVEN);
            BigDecimal standardDeviation = variance.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : variance.sqrt(SQRT_CONTEXT).setScale(8, RoundingMode.HALF_EVEN);
            BigDecimal offset = stdDevMultiplier.multiply(standardDeviation);
            BigDecimal upper = middle.add(offset);
            BigDecimal lower = middle.subtract(offset);
            BigDecimal width = middle.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : upper.subtract(lower).divide(middle, 8, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100));
            BigDecimal bandRange = upper.subtract(lower);
            BigDecimal percentB = bandRange.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.valueOf(0.5).setScale(8, RoundingMode.HALF_EVEN)
                    : close.subtract(lower).divide(bandRange, 8, RoundingMode.HALF_EVEN);
            return Optional.of(new BollingerBands.BollingerValue(upper, middle, lower, width, percentB));
        }
    }

    private static final class ReferenceDonchian {
        private final int period;
        private final Deque<BigDecimal> highs = new ArrayDeque<>();
        private final Deque<BigDecimal> lows = new ArrayDeque<>();

        ReferenceDonchian(int period) {
            this.period = period;
        }

        Optional<DonchianChannel.DonchianValue> update(CandleEvent candle) {
            highs.addLast(candle.high());
            lows.addLast(candle.low());
            if (highs.size() > period) {
                highs.removeFirst();
                lows.removeFirst();
            }
            if (highs.size() < period) {
                return Optional.empty();
            }
            BigDecimal upper = scanMax(highs);
            BigDecimal lower = scanMin(lows);
            BigDecimal channelRange = upper.subtract(lower);
            BigDecimal position = channelRange.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.valueOf(0.5).setScale(8, RoundingMode.HALF_EVEN)
                    : candle.close().subtract(lower).divide(channelRange, 8, RoundingMode.HALF_EVEN);
            return Optional.of(new DonchianChannel.DonchianValue(upper, lower, position));
        }
    }

    private static final class ReferencePriceAction {
        private static final int SCALE = 8;
        private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
        private final int lookback;
        private final int swingStrength;
        private final Deque<BigDecimal> priorHighs = new ArrayDeque<>();
        private final Deque<BigDecimal> priorLows = new ArrayDeque<>();
        private final Deque<CandleEvent> pivotWindow = new ArrayDeque<>();
        private final Deque<Swing> swings = new ArrayDeque<>();
        private CandleEvent previous;
        private int streak;
        private long index = -1;

        ReferencePriceAction(int lookback, int swingStrength) {
            this.lookback = lookback;
            this.swingStrength = swingStrength;
        }

        PriceAction.PriceActionValue update(CandleEvent candle) {
            index++;
            BigDecimal close = candle.close();

            int previousBreak = 0;
            if (previous != null) {
                if (close.compareTo(previous.high()) > 0) {
                    previousBreak = 1;
                } else if (close.compareTo(previous.low()) < 0) {
                    previousBreak = -1;
                }
            }
            int direction = close.compareTo(candle.open());
            streak = direction > 0 ? (streak > 0 ? streak + 1 : 1) : direction < 0 ? (streak < 0 ? streak - 1 : -1) : 0;

            BigDecimal recentHighDistance = BigDecimal.ZERO;
            BigDecimal recentLowDistance = BigDecimal.ZERO;
            if (priorHighs.size() == lookback) {
                recentHighDistance = percentFromClose(close, scanMax(priorHighs));
                recentLowDistance = percentFromClose(close, scanMin(priorLows));
            }

            detectPivot(candle);
            swings.removeIf(swing -> swing.index() < index - lookback);

            BigDecimal range = candle.high().subtract(candle.low());
            BigDecimal[] body;
            if (range.compareTo(BigDecimal.ZERO) == 0) {
                body = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            } else {
                BigDecimal bodyTop = candle.open().max(close);
                BigDecimal bodyBottom = candle.open().min(close);
                body = new BigDecimal[] {
                        bodyTop.subtract(bodyBottom).divide(range, SCALE, RoundingMode.HALF_EVEN),
                        candle.high().subtract(bodyTop).divide(range, SCALE, RoundingMode.HALF_EVEN),
                        bodyBottom.subtract(candle.low()).divide(range, SCALE, RoundingMode.HALF_EVEN)
                };
            }

            BigDecimal support = null;
            BigDecimal resistance = null;
            for (Swing swing : swings) {
                if (swing.price().compareTo(close) < 0) {
                    support = support == null ? swing.price() : support.max(swing.price());
                }
                if (swing.price().compareTo(close) > 0) {
                    resistance = resistance == null ? swing.price() : resistance.min(swing.price());
                }
            }

            PriceAction.PriceActionValue value = new PriceAction.PriceActionValue(
                    body[0], body[1], body[2], streak, previousBreak,
                    recentHighDistance, recentLowDistance,
                    swingTrend(true), swingTrend(false),
                    support == null ? BigDecimal.ZERO : percentFromClose(close, support),
                    resistance == null ? BigDecimal.ZERO : percentFromClose(close, resistance).negate());

            previous = candle;
            priorHighs.addLast(candle.high());
            priorLows.addLast(candle.low());
            if (priorHighs.size() > lookback) {
                priorHighs.removeFirst();
                priorLows.removeFirst();
            }
            return value;
        }

        private void detectPivot(CandleEvent candle) {
            pivotWindow.addLast(candle);
            if (pivotWindow.size() > 2 * swingStrength + 1) {
                pivotWindow.removeFirst();
            }
            if (pivotWindow.size() < 2 * swingStrength + 1) {
                return;
            }
            List<CandleEvent> window = new ArrayList<>(pivotWindow);
            CandleEvent candidate = window.get(swingStrength);
            boolean isHigh = true;
            boolean isLow = true;
            for (int i = 0; i < window.size(); i++) {
                if (i == swingStrength) {
                    continue;
                }
                int highCmp = candidate.high().compareTo(window.get(i).high());
                int lowCmp = candidate.low().compareTo(window.get(i).low());
                if (i < swingStrength) {
                    isHigh &= highCmp > 0;
                    isLow &= lowCmp < 0;
                } else {
                    isHigh &= highCmp >= 0;
                    isLow &= lowCmp <= 0;
                }
            }
            long candidateIndex = index - swingStrength;
            if (isHigh) {
                swings.addLast(new Swing(candidateIndex, candidate.high(), true));
            }
            if (isLow) {
                swings.addLast(new Swing(candidateIndex, candidate.low(), false));
            }
        }

        private int swingTrend(boolean highs) {
            BigDecimal last = null;
            BigDecimal beforeLast = null;
            for (Swing swing : swings) {
                if (swing.high() == highs) {
                    beforeLast = last;
                    last = swing.price();
                }
            }
            return beforeLast == null ? 0 : last.compareTo(beforeLast);
        }

        private static BigDecimal percentFromClose(BigDecimal close, BigDecimal level) {
            if (close.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return close.subtract(level).divide(close, SCALE, RoundingMode.HALF_EVEN).multiply(HUNDRED);
        }

        private record Swing(long index, BigDecimal price, boolean high) {}
    }

    private static BigDecimal scanMax(Deque<BigDecimal> values) {
        BigDecimal result = null;
        for (BigDecimal value : values) {
            result = result == null ? value : result.max(value);
        }
        return result;
    }

    private static BigDecimal scanMin(Deque<BigDecimal> values) {
        BigDecimal result = null;
        for (BigDecimal value : values) {
            result = result == null ? value : result.min(value);
        }
        return result;
    }
}
