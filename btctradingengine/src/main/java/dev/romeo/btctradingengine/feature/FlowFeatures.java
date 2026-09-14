package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Flow layer: order/volume pressure. MFI embeds volume, so unlike the pure context fields it is
 * directional enough to feed the score average (see MfiRule).
 *
 * The delta/CVD fields (issue #11) split volume by aggressor side and have no rule yet: wiring one
 * into the score average would change its denominator and require re-running the backtest sweep.
 * The taker split comes from klines in backtests and warmup, and from aggTrades live. The size split
 * (largeCvd, largeVolumeShare) only exists live and is 0 everywhere else.
 *
 * VPIN (issue #13) is not directional either: it only gates new entries, see VpinEntryGuard.
 *
 * Order book imbalance belongs in this group too, but it needs a depth stream and does not come
 * out of CandleEvent - issue #10 will add it as a component here.
 *
 * While MFI is warming up the neutral 50 is reported, the same convention rsiValue uses - a zero
 * here would be indistinguishable from a genuinely fully-oversold reading. For the delta/CVD fields
 * 0 already is the neutral reading, and it is also what a candle without flow data reports. VPIN is
 * 0 until enough volume buckets have completed.
 */
public record FlowFeatures(
        BigDecimal mfi,                   // Money Flow Index [0-100]
        BigDecimal volumeDelta,           // aggressive buy - sell volume of the candle (base units)
        BigDecimal deltaRatio,            // volumeDelta / taker volume of the candle [-1, 1]
        BigDecimal cvd,                   // sum of volumeDelta over the CVD window (base units)
        BigDecimal cvdRatio,              // cvd / taker volume of the window [-1, 1]
        BigDecimal largeCvd,              // cvd of large trades only (live only)
        BigDecimal largeVolumeShare,      // large trade volume / taker volume of the window [0-1] (live only)
        BigDecimal vpin                   // approximate VPIN [0-1], 0 while warming up
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

        public Builder mfi(BigDecimal mfi) { this.mfi = mfi; return this; }
        public Builder volumeDelta(BigDecimal volumeDelta) { this.volumeDelta = volumeDelta; return this; }
        public Builder deltaRatio(BigDecimal deltaRatio) { this.deltaRatio = deltaRatio; return this; }
        public Builder cvd(BigDecimal cvd) { this.cvd = cvd; return this; }
        public Builder cvdRatio(BigDecimal cvdRatio) { this.cvdRatio = cvdRatio; return this; }
        public Builder largeCvd(BigDecimal largeCvd) { this.largeCvd = largeCvd; return this; }
        public Builder largeVolumeShare(BigDecimal largeVolumeShare) { this.largeVolumeShare = largeVolumeShare; return this; }
        public Builder vpin(BigDecimal vpin) { this.vpin = vpin; return this; }

        public FlowFeatures build() {
            return new FlowFeatures(mfi, volumeDelta, deltaRatio, cvd, cvdRatio, largeCvd, largeVolumeShare, vpin);
        }
    }
}
