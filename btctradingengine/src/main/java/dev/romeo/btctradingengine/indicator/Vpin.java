package dev.romeo.btctradingengine.indicator;

import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Approximate VPIN - volume-synchronized probability of informed trading (issue #13).
 *
 * Candle volume is poured into buckets of equal volume instead of equal time, so a burst of activity
 * produces more buckets rather than one diluted candle. For each full bucket the order flow imbalance
 * |buy - sell| / bucket volume is recorded, and VPIN is the mean of the last {@code buckets} of them,
 * in [0, 1]. A candle that spills over a bucket boundary is split keeping its buy share.
 *
 * The bucket size adapts to the market: bucketCandles times the average taker volume per candle over
 * the last buckets * bucketCandles candles, fixed when the bucket opens. That removes an absolute
 * volume threshold that would go stale as activity changes, and keeps the first buckets after startup
 * from being sized off a single candle.
 *
 * It is NOT a true probability: it only measures how one-sided the aggressor flow was, and inherits
 * every error of the buy/sell split (klines in backtests, aggTrades live). Candles without flow data
 * are skipped. Returns 0 until {@code buckets} buckets have completed - the "not available yet"
 * convention of the flow group.
 */
public class Vpin {
    private static final int SCALE = 12;
    private static final int OUTPUT_SCALE = 8;

    private final int buckets;
    private final int bucketCandles;
    private final int volumeWindow;

    private final Deque<BigDecimal> candleVolumes = new ArrayDeque<>();
    private BigDecimal windowVolume = BigDecimal.ZERO;

    private final Deque<BigDecimal> imbalances = new ArrayDeque<>();
    private BigDecimal imbalanceSum = BigDecimal.ZERO;

    /** Size of the bucket being filled; null while no bucket is open. */
    private BigDecimal bucketSize;
    private BigDecimal bucketBuy = BigDecimal.ZERO;
    private BigDecimal bucketSell = BigDecimal.ZERO;

    public Vpin(int buckets, int bucketCandles) {
        this.buckets = buckets;
        this.bucketCandles = bucketCandles;
        this.volumeWindow = buckets * bucketCandles;
    }

    public BigDecimal update(CandleEvent candle) {
        TradeFlow flow = candle.flow();
        if (!flow.hasTakerSplit()) {
            return value();
        }
        BigDecimal volume = flow.takerBuyVolume().add(flow.takerSellVolume());
        if (volume.signum() <= 0) {
            return value();
        }
        trackVolume(volume);

        BigDecimal buyShare = flow.takerBuyVolume().divide(volume, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal remaining = volume;
        while (remaining.signum() > 0) {
            if (bucketSize == null && !openBucket()) {
                break;
            }
            BigDecimal room = bucketSize.subtract(bucketBuy).subtract(bucketSell);
            BigDecimal poured = remaining.min(room);
            BigDecimal buyPart = poured.multiply(buyShare).setScale(SCALE, RoundingMode.HALF_EVEN);
            bucketBuy = bucketBuy.add(buyPart);
            // sell is the remainder, so buy + sell adds up to exactly what was poured
            bucketSell = bucketSell.add(poured.subtract(buyPart));
            remaining = remaining.subtract(poured);
            if (poured.compareTo(room) == 0) {
                closeBucket();
            }
        }
        return value();
    }

    private boolean openBucket() {
        BigDecimal averageCandleVolume = windowVolume.divide(
                BigDecimal.valueOf(candleVolumes.size()), SCALE, RoundingMode.HALF_EVEN);
        BigDecimal size = averageCandleVolume.multiply(BigDecimal.valueOf(bucketCandles));
        if (size.signum() <= 0) {
            return false;
        }
        bucketSize = size;
        return true;
    }

    private void closeBucket() {
        BigDecimal imbalance = bucketBuy.subtract(bucketSell).abs()
                .divide(bucketSize, SCALE, RoundingMode.HALF_EVEN);
        imbalances.addLast(imbalance);
        imbalanceSum = imbalanceSum.add(imbalance);
        if (imbalances.size() > buckets) {
            imbalanceSum = imbalanceSum.subtract(imbalances.removeFirst());
        }
        bucketSize = null;
        bucketBuy = BigDecimal.ZERO;
        bucketSell = BigDecimal.ZERO;
    }

    private void trackVolume(BigDecimal volume) {
        candleVolumes.addLast(volume);
        windowVolume = windowVolume.add(volume);
        if (candleVolumes.size() > volumeWindow) {
            windowVolume = windowVolume.subtract(candleVolumes.removeFirst());
        }
    }

    private BigDecimal value() {
        if (imbalances.size() < buckets) {
            return BigDecimal.ZERO;
        }
        return imbalanceSum.divide(BigDecimal.valueOf(buckets), OUTPUT_SCALE, RoundingMode.HALF_EVEN);
    }
}
