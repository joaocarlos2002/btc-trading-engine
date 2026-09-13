package dev.romeo.btctradingengine.feature;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Features grouped by the role they play in a decision, not as one flat list:
 *
 * <pre>
 * REGIME   (ADX, ATR%, BB Width)      -> decides IF we trade and WHICH signal family is valid
 * SCORE    (MFI + RSI/SMA/MACD)       -> decides WHEN to enter
 * CONTEXTO (VWAP, Donchian, %B)       -> modulates conviction / confirms, never signals alone
 * PRICE ACTION (candles, swings, S/R) -> raw structure read from the candles, no rule yet
 * </pre>
 *
 * The grouping is not cosmetic. This record used to be 18 positional components, a dozen of them
 * adjacent BigDecimals: swapping two of them compiled cleanly and silently corrupted the features.
 * Each group now has its own builder, so a field can only be set by name.
 *
 * The flat accessors below (rsiValue(), atrValue(), ...) delegate into {@link CoreFeatures} so the
 * existing rules, the backtest and the tests keep reading the fields they always read.
 */
public record FeatureVector(
        String instrument,
        Instant timestamp,

        CoreFeatures core,                // everything that existed before the regime/context split
        RegimeFeatures regime,            // adx, plusDi, minusDi, atrPercent, bbWidth
        ContextFeatures context,          // vwap, vwapDistance, bbPercentB, donchian*
        FlowFeatures flow,                // mfi, volume delta, cvd (+ orderBookImbalance - issue #10)
        DerivFeatures deriv,             // empty until issue #9 (openInterest, funding)
        PriceActionFeatures priceAction,  // candle anatomy, streak, breakouts, swings, S/R (issue #6)

        BigDecimal price,                 // close price (referencia)
        int tickCount                     // ticks na vela
) {

    public BigDecimal returnPct1m() { return core.returnPct1m(); }
    public BigDecimal volatility5m() { return core.volatility5m(); }
    public BigDecimal volatility20m() { return core.volatility20m(); }
    public BigDecimal smaDistance() { return core.smaDistance(); }
    public BigDecimal emaDistance() { return core.emaDistance(); }
    public BigDecimal rsiValue() { return core.rsiValue(); }
    public BigDecimal macdValue() { return core.macdValue(); }
    public BigDecimal macdSignal() { return core.macdSignal(); }
    public BigDecimal atrValue() { return core.atrValue(); }
    public BigDecimal volumeRatio() { return core.volumeRatio(); }
    public BigDecimal highLowRatio() { return core.highLowRatio(); }
    public BigDecimal closePosition() { return core.closePosition(); }
    public int hourOfDay() { return core.hourOfDay(); }
    public int dayOfWeek() { return core.dayOfWeek(); }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Keeps the flat, named setters the call sites already use and assembles the groups internally,
     * so no caller ever has to get a group's component order right.
     */
    public static class Builder {
        private String instrument;
        private Instant timestamp;
        private BigDecimal price;
        private int tickCount;

        private final CoreFeatures.Builder core = CoreFeatures.builder();
        private final RegimeFeatures.Builder regime = RegimeFeatures.builder();
        private final ContextFeatures.Builder context = ContextFeatures.builder();
        private final FlowFeatures.Builder flow = FlowFeatures.builder();
        private DerivFeatures deriv = DerivFeatures.empty();
        private PriceActionFeatures priceAction = PriceActionFeatures.empty();

        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder price(BigDecimal price) { this.price = price; return this; }
        public Builder tickCount(int tickCount) { this.tickCount = tickCount; return this; }

        public Builder returnPct1m(BigDecimal returnPct1m) { core.returnPct1m(returnPct1m); return this; }
        public Builder volatility5m(BigDecimal volatility5m) { core.volatility5m(volatility5m); return this; }
        public Builder volatility20m(BigDecimal volatility20m) { core.volatility20m(volatility20m); return this; }
        public Builder smaDistance(BigDecimal smaDistance) { core.smaDistance(smaDistance); return this; }
        public Builder emaDistance(BigDecimal emaDistance) { core.emaDistance(emaDistance); return this; }
        public Builder rsiValue(BigDecimal rsiValue) { core.rsiValue(rsiValue); return this; }
        public Builder macdValue(BigDecimal macdValue) { core.macdValue(macdValue); return this; }
        public Builder macdSignal(BigDecimal macdSignal) { core.macdSignal(macdSignal); return this; }
        public Builder atrValue(BigDecimal atrValue) { core.atrValue(atrValue); return this; }
        public Builder volumeRatio(BigDecimal volumeRatio) { core.volumeRatio(volumeRatio); return this; }
        public Builder highLowRatio(BigDecimal highLowRatio) { core.highLowRatio(highLowRatio); return this; }
        public Builder closePosition(BigDecimal closePosition) { core.closePosition(closePosition); return this; }
        public Builder hourOfDay(int hourOfDay) { core.hourOfDay(hourOfDay); return this; }
        public Builder dayOfWeek(int dayOfWeek) { core.dayOfWeek(dayOfWeek); return this; }

        public Builder adx(BigDecimal adx) { regime.adx(adx); return this; }
        public Builder plusDi(BigDecimal plusDi) { regime.plusDi(plusDi); return this; }
        public Builder minusDi(BigDecimal minusDi) { regime.minusDi(minusDi); return this; }
        public Builder atrPercent(BigDecimal atrPercent) { regime.atrPercent(atrPercent); return this; }
        public Builder bbWidth(BigDecimal bbWidth) { regime.bbWidth(bbWidth); return this; }

        public Builder vwap(BigDecimal vwap) { context.vwap(vwap); return this; }
        public Builder vwapDistance(BigDecimal vwapDistance) { context.vwapDistance(vwapDistance); return this; }
        public Builder bbPercentB(BigDecimal bbPercentB) { context.bbPercentB(bbPercentB); return this; }
        public Builder donchianUpper(BigDecimal donchianUpper) { context.donchianUpper(donchianUpper); return this; }
        public Builder donchianLower(BigDecimal donchianLower) { context.donchianLower(donchianLower); return this; }
        public Builder donchianPosition(BigDecimal donchianPosition) { context.donchianPosition(donchianPosition); return this; }

        public Builder mfi(BigDecimal mfi) { flow.mfi(mfi); return this; }
        public Builder volumeDelta(BigDecimal volumeDelta) { flow.volumeDelta(volumeDelta); return this; }
        public Builder deltaRatio(BigDecimal deltaRatio) { flow.deltaRatio(deltaRatio); return this; }
        public Builder cvd(BigDecimal cvd) { flow.cvd(cvd); return this; }
        public Builder cvdRatio(BigDecimal cvdRatio) { flow.cvdRatio(cvdRatio); return this; }
        public Builder largeCvd(BigDecimal largeCvd) { flow.largeCvd(largeCvd); return this; }
        public Builder largeVolumeShare(BigDecimal largeVolumeShare) { flow.largeVolumeShare(largeVolumeShare); return this; }
        public Builder deriv(DerivFeatures deriv) { this.deriv = deriv; return this; }
        public Builder priceAction(PriceActionFeatures priceAction) { this.priceAction = priceAction; return this; }

        public FeatureVector build() {
            return new FeatureVector(
                    instrument, timestamp,
                    core.build(), regime.build(), context.build(), flow.build(), deriv, priceAction,
                    price, tickCount
            );
        }
    }
}
