package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;

/**
 * Flow layer: order/volume pressure. MFI embeds volume, so unlike the pure context fields it is
 * directional enough to feed the score average (see MfiRule).
 *
 * Order book imbalance belongs in this group too, but it needs a depth stream and does not come
 * out of CandleEvent - issue #10 will add it as a component here.
 *
 * While MFI is warming up the neutral 50 is reported, the same convention rsiValue uses - a zero
 * here would be indistinguishable from a genuinely fully-oversold reading.
 */
public record FlowFeatures(
        BigDecimal mfi                    // Money Flow Index [0-100]
) {

    public static FlowFeatures empty() {
        return of(BigDecimal.ZERO);
    }

    public static FlowFeatures of(BigDecimal mfi) {
        return new FlowFeatures(mfi);
    }
}
