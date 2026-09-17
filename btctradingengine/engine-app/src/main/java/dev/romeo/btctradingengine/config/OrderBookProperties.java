package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

/** {@code orderbook.*}: the spot order book poller (issue #10), mainnet by default. */
@Validated
@ConfigurationProperties("orderbook")
public record OrderBookProperties(
        boolean enabled,
        @Name("rest.url") @NotNull String restUrl,
        @Name("poll.seconds") @Min(value = 1, message = "orderbook.poll.seconds must be positive") long pollSeconds,
        @Name("depth.levels") int depthLevels
) {
    private static final Set<Integer> BINANCE_LIMITS = Set.of(5, 10, 20, 50, 100, 500, 1000, 5000);

    @AssertTrue(message = "orderbook.depth.levels must be one of 5, 10, 20, 50, 100, 500, 1000, 5000")
    public boolean isDepthLevelsABinanceLimit() {
        return BINANCE_LIMITS.contains(depthLevels);
    }
}
