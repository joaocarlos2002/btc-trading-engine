package dev.romeo.btctradingengine.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * {@code dashboard.*} settings of the web layer: the WebSocket origins (issue #66) and the backtest job
 * limits (issues #83 and #108). The login ({@code dashboard.auth.*}) is read by DashboardSecurityConfig.
 */
@Validated
@ConfigurationProperties("dashboard")
public record DashboardProperties(
        /* Origins allowed to open the /ws/live WebSocket; blanks are dropped. */
        @Name("allowed.origins") @DefaultValue({"http://localhost:8080", "http://127.0.0.1:8080"})
        List<String> allowedOrigins,
        @Name("backtest.max.concurrent") @DefaultValue("1")
        @Min(value = 1, message = "dashboard.backtest.max.concurrent must be positive")
        int backtestMaxConcurrent,
        @Name("backtest.max.jobs") @DefaultValue("50")
        @Min(value = 1, message = "dashboard.backtest.max.jobs must be positive")
        int backtestMaxJobs
) {
    public DashboardProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
