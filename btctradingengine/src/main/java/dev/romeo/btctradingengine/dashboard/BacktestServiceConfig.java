package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.backtest.BacktestService;
import dev.romeo.btctradingengine.backtest.BinanceBacktestDataLoader;
import dev.romeo.btctradingengine.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the backtest service for the dashboard (issues #83 and #108). The limits are Spring
 * properties rather than Config keys since only the dashboard runs backtests:
 * {@code dashboard.backtest.max.concurrent} (default 1) and {@code dashboard.backtest.max.jobs}
 * (finished jobs kept in memory, default 50).
 */
@Configuration
public class BacktestServiceConfig {

    @Bean(destroyMethod = "close")
    public BacktestService backtestService(
            @Value("${dashboard.backtest.max.concurrent:1}") int maxConcurrent,
            @Value("${dashboard.backtest.max.jobs:50}") int maxJobs) {
        return BacktestService.create(new BinanceBacktestDataLoader(), Config.getTradingInitialCapital(),
                maxConcurrent, maxJobs);
    }
}
