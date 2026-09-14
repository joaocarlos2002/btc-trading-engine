package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Flow layer: order/volume pressure. MFI embeds volume, so unlike the pure context fields it is
 * directional enough to feed the score average (see MfiRule).
 *
 * The delta/CVD fields (issue #11) split volume by aggressor side and have no rule yet: wiring one
 * into the score average would change its denominator and require re-running the backtest sweep.
 * The taker split comes from klines in backtests and warmup, and from aggTrades live. The size split
 * (largeCvd, largeVolumeShare) needs trade sizes: live it always exists, in backtests only with
 * /backtest?sizeSplit=true (issue #51), and it is 0 otherwise.
 *
 * VPIN (issue #13) is not directional either: it only gates new entries, see VpinEntryGuard.
 * Absorption (issue #12) is directional but has no rule yet, for the same reason as the CVD fields.
 *
 * orderBookImbalance (issue #10) comes from polling the order book outside the candle stream. It is
 * live only and, unlike the other fields, null when unavailable: an imbalance of 0 is a real reading
 * (a book with only asks). Like VPIN it only gates new entries, see OrderBookEntryGuard.
 *
 * While MFI is warming up the neutral 50 is reported, the same convention rsiValue uses - a zero
 * here would be indistinguishable from a genuinely fully-oversold reading. For the delta/CVD and
 * absorption fields 0 already is the neutral reading, and it is also what a candle without flow data
 * reports. VPIN is 0 until enough volume buckets have completed.
 */
public record FlowFeatures(
        BigDecimal mfi,                   // Money Flow Index [0-100]
        BigDecimal volumeDelta,           // aggressive buy - sell volume of the candle (base units)
        BigDecimal deltaRatio,            // volumeDelta / taker volume of the candle [-1, 1]
        BigDecimal cvd,                   // sum of volumeDelta over the CVD window (base units)
        BigDecimal cvdRatio,              // cvd / taker volume of the window [-1, 1]
        BigDecimal largeCvd,              // cvd of large trades only (needs trade sizes)
        BigDecimal largeVolumeShare,      // large trade volume / taker volume of the window [0-1] (needs trade sizes)
        BigDecimal vpin,                  // approximate VPIN [0-1], 0 while warming up
        BigDecimal absorption,            // candle absorption [-1, 1]: + buy absorption, - sell absorption
        BigDecimal absorptionSum,         // absorption summed over feature.absorption.window candles
        BigDecimal orderBookImbalance     // mean book bid share [0-1] during the candle; null when unavailable
) {

    public static FlowFeatures empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BigDecimal mfi = BigDecimal.ZERO;
        private BigDecimal volumeDelta = BigDecimal.ZERO;
        private BigDecimal deltaRatio = BigDecimal.ZERO;
        private BigDecimal cvd = BigDecimal.ZERO;
        private BigDecimal cvdRatio = BigDecimal.ZERO;
        private BigDecimal largeCvd = BigDecimal.ZERO;
        private BigDecimal largeVolumeShare = BigDecimal.ZERO;
        private BigDecimal vpin = BigDecimal.ZERO;
        private BigDecimal absorption = BigDecimal.ZERO;
        private BigDecimal absorptionSum = BigDecimal.ZERO;
        private BigDecimal orderBookImbalance;

        public Builder mfi(BigDecimal mfi) { this.mfi = mfi; return this; }
        public Builder volumeDelta(BigDecimal volumeDelta) { this.volumeDelta = volumeDelta; return this; }
        public Builder deltaRatio(BigDecimal deltaRatio) { this.deltaRatio = deltaRatio; return this; }
        public Builder cvd(BigDecimal cvd) { this.cvd = cvd; return this; }
        public Builder cvdRatio(BigDecimal cvdRatio) { this.cvdRatio = cvdRatio; return this; }
        public Builder largeCvd(BigDecimal largeCvd) { this.largeCvd = largeCvd; return this; }
        public Builder largeVolumeShare(BigDecimal largeVolumeShare) { this.largeVolumeShare = largeVolumeShare; return this; }
        public Builder vpin(BigDecimal vpin) { this.vpin = vpin; return this; }
        public Builder absorption(BigDecimal absorption) { this.absorption = absorption; return this; }
        public Builder absorptionSum(BigDecimal absorptionSum) { this.absorptionSum = absorptionSum; return this; }
        public Builder orderBookImbalance(BigDecimal orderBookImbalance) { this.orderBookImbalance = orderBookImbalance; return this; }

        public FlowFeatures build() {
            return new FlowFeatures(mfi, volumeDelta, deltaRatio, cvd, cvdRatio, largeCvd, largeVolumeShare,
                    vpin, absorption, absorptionSum, orderBookImbalance);
        }
    }
}
