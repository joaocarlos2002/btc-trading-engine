package dev.romeo.btctradingengine.feature;

import dev.romeo.btctradingengine.model.CandleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class FeatureBuffer {
    private static final Logger logger = LoggerFactory.getLogger(FeatureBuffer.class);
    private static final int BUFFER_SIZE = 100;

    private final Deque<CandleEvent> buffer = new ArrayDeque<>(BUFFER_SIZE);

    public void add(CandleEvent candle) {
        buffer.addLast(candle);
        if (buffer.size() > BUFFER_SIZE) {
            buffer.removeFirst();
        }
    }

    public Optional<BigDecimal> volatility(int periods) {
        if (buffer.size() < periods) {
            return Optional.empty();
        }

        var closes = buffer.stream()
                .skip(buffer.size() - periods)
                .map(CandleEvent::close)
                .toList();

        // Calculate returns (price changes in %)
        var returns = new java.util.ArrayList<BigDecimal>();
        for (int i = 1; i < closes.size(); i++) {
            BigDecimal prevClose = closes.get(i - 1);
            BigDecimal currClose = closes.get(i);
            if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ret = currClose.subtract(prevClose)
                        .divide(prevClose, 10, RoundingMode.HALF_UP);
                returns.add(ret);
            }
        }

        if (returns.isEmpty()) {
            return Optional.empty();
        }

        // Calculate mean of returns
        BigDecimal meanReturn = returns.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 10, RoundingMode.HALF_UP);

        // Calculate variance of returns
        BigDecimal variance = returns.stream()
                .map(r -> r.subtract(meanReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 10, RoundingMode.HALF_UP);

        // StdDev of returns * 100 = volatility in %
        return Optional.of(sqrt(variance).multiply(BigDecimal.valueOf(100)));
    }

    public Optional<BigDecimal> averageVolume(int periods) {
        if (buffer.size() < periods) {
            return Optional.empty();
        }

        BigDecimal sum = buffer.stream()
                .skip(buffer.size() - periods)
                .map(CandleEvent::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Optional.of(sum.divide(BigDecimal.valueOf(periods), 8, RoundingMode.HALF_UP));
    }

    public int size() {
        return buffer.size();
    }

    public Optional<CandleEvent> getLast() {
        return Optional.ofNullable(buffer.peekLast());
    }

    private BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal x = value;
        BigDecimal y = value.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);

        for (int i = 0; i < 10; i++) {
            x = y.add(value.divide(y, 10, RoundingMode.HALF_UP))
                    .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);

            if (x.subtract(y).abs().compareTo(new BigDecimal("0.0000000001")) < 0) {
                break;
            }
            y = x;
        }

        return x;
    }
}

