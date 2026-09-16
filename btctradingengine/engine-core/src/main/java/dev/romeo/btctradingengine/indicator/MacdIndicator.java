package dev.romeo.btctradingengine.indicator;

import java.math.BigDecimal;
import java.util.Optional;

public class MacdIndicator {
    private final Ema ema12;
    private final Ema ema26;
    private final Ema emaSignal;

    public MacdIndicator() {
        this(12, 26, 9);
    }

    public MacdIndicator(int fastPeriod, int slowPeriod, int signalPeriod) {
        this.ema12 = new Ema(fastPeriod);
        this.ema26 = new Ema(slowPeriod);
        this.emaSignal = new Ema(signalPeriod);
    }

    public Optional<MacdValue> update(BigDecimal close) {
        BigDecimal v12 = ema12.update(close);
        BigDecimal v26 = ema26.update(close);
        BigDecimal macd = v12.subtract(v26);
        BigDecimal signal = emaSignal.update(macd);
        BigDecimal histogram = macd.subtract(signal);

        return Optional.of(new MacdValue(macd, signal, histogram));
    }

    public record MacdValue(
            BigDecimal macd,
            BigDecimal signal,
            BigDecimal histogram
    ) {}
}

