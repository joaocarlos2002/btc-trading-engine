package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.backtest.BacktestService;
import dev.romeo.btctradingengine.backtest.BinanceBacktestDataLoader;
import dev.romeo.btctradingengine.config.DashboardProperties;
import dev.romeo.btctradingengine.config.TradingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the backtest service for the dashboard (issues #83 and #108): {@code dashboard.backtest.max.concurrent}
 * (default 1) and {@code dashboard.backtest.max.jobs} (finished jobs kept in memory, default 50).
 */
@Configuration(proxyBeanMethods = false)
public class BacktestServiceConfig {

    @Bean(destroyMethod = "close")
    public BacktestService backtestService(BinanceBacktestDataLoader backtestDataLoader, TradingProperties trading,
                                           DashboardProperties dashboard) {
        return BacktestService.create(backtestDataLoader, trading.initialCapitalUsdt(),
                dashboard.backtestMaxConcurrent(), dashboard.backtestMaxJobs());
    }
}
