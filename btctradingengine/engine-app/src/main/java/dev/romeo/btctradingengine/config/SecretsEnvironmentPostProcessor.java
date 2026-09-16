package dev.romeo.btctradingengine.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Maps the BTC_ENGINE_* variables onto their property keys (issue #73), keeping the precedence the
 * static Config had: a non-blank environment variable, then a non-blank value in .env, then
 * application.properties (and the active profile's file).
 *
 * <p>A plain {@code ${BTC_ENGINE_DB_PASSWORD:}} placeholder would not do: .env.example ships the
 * variables blank, and a blank value must fall through to the property instead of replacing it
 * (e.g. {@code trading.oco.enabled=true}). The resulting property source sits right after the system
 * environment, so command-line arguments and system properties still win over it.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    public static final String PROPERTY_SOURCE_NAME = "btcEngineEnvironment";

    /** Environment variable -> property key. */
    static final Map<String, String> VARIABLES = Map.of(
            "BTC_ENGINE_DB_PASSWORD", "db.password",
            "BTC_ENGINE_BINANCE_API_KEY", "binance.api.key",
            "BTC_ENGINE_BINANCE_API_SECRET", "binance.api.secret",
            "BTC_ENGINE_TRADING_OCO_ENABLED", "trading.oco.enabled",
            "BTC_ENGINE_TRADING_SIZING_STRATEGY", "trading.sizing.strategy",
            "BTC_ENGINE_DISCORD_WEBHOOK_URL", "alert.discord.webhook.url");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> dotEnv = DotEnv.load(Path.of(".env"), Path.of("../.env"));
        Map<String, Object> resolved = resolve(environment::getProperty, dotEnv);
        if (resolved.isEmpty()) {
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, resolved);
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }
    }

    /** Property key -> value for every variable set (non-blank) in the environment or, failing that, in .env. */
    static Map<String, Object> resolve(Function<String, String> environment, Map<String, String> dotEnv) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        VARIABLES.forEach((variable, key) -> {
            String value = environment.apply(variable);
            if (value == null || value.isBlank()) {
                value = dotEnv.get(variable);
            }
            if (value != null && !value.isBlank()) {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    /** After the config data files are loaded, so it can be placed relative to them. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
