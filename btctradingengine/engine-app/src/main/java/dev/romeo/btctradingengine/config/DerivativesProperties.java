package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

/** {@code derivatives.*}: the USD-M perpetual context (issue #53), always read from mainnet. */
@Validated
@ConfigurationProperties("derivatives")
public record DerivativesProperties(
        boolean enabled,
        @Name("poll.seconds") @Min(value = 1, message = "derivatives.poll.seconds must be positive")
        long pollSeconds,
        @Name("basis.poll.seconds") @Min(value = 1, message = "derivatives.basis.poll.seconds must be positive")
        long basisPollSeconds,
        @Name("stale.seconds") @Min(value = 1, message = "derivatives.stale.seconds must be positive")
        long staleSeconds,
        @Name("open.interest.change.minutes")
        @Min(value = 1, message = "derivatives.open.interest.change.minutes must be positive")
        long openInterestChangeMinutes,
        /* Blank means a folder under java.io.tmpdir, so a fresh checkout needs no setup. */
        @Name("metrics.cache.dir") String metricsCacheDir
) {
    public Duration staleAfter() {
        return Duration.ofSeconds(staleSeconds);
    }

    public Duration openInterestChangeWindow() {
        return Duration.ofMinutes(openInterestChangeMinutes);
    }

    public Path metricsCachePath() {
        return CacheDirs.resolve(metricsCacheDir, "metrics");
    }
}
