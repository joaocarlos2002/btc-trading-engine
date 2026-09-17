package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/** {@code price.bus.*}: bounded queues of the critical PriceEventBus subscribers (issue #85). */
@Validated
@ConfigurationProperties("price.bus")
public record PriceBusProperties(
        @Name("queue.capacity") @Min(value = 1, message = "price.bus.queue.capacity must be positive")
        int queueCapacity,
        @Name("block.timeout.ms") @Min(value = 0, message = "price.bus.block.timeout.ms cannot be negative")
        long blockTimeoutMs
) {
}
