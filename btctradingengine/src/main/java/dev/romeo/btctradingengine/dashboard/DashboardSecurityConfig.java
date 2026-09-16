package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.config.Config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Guards the endpoints that place orders or start a backtest with {@link ApiTokenInterceptor}
 * (issue #66). The read-only endpoints stay open: together with {@code server.address=127.0.0.1}
 * they are only reachable from the machine running the bot.
 */
@Configuration
public class DashboardSecurityConfig implements WebMvcConfigurer {

    static final String[] PROTECTED_PATHS = {"/api/trades/manual/**", "/api/backtest"};

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiTokenInterceptor(Config.getDashboardApiToken()))
                .addPathPatterns(PROTECTED_PATHS);
    }
}
