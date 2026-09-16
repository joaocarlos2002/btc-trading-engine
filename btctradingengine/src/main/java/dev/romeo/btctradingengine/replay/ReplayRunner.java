package dev.romeo.btctradingengine.replay;

import dev.romeo.btctradingengine.config.EngineConfiguration;
import dev.romeo.btctradingengine.config.FeatureProperties;
import dev.romeo.btctradingengine.config.MarketProperties;
import dev.romeo.btctradingengine.config.StartupSettingsValidator;
import dev.romeo.btctradingengine.config.TradingProperties;
import dev.romeo.btctradingengine.feature.IndicatorPeriods;
import dev.romeo.btctradingengine.persistence.DatabaseTickReader;
import dev.romeo.btctradingengine.prediction.PredictionSettings;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Instant;

/**
 * Replays recorded ticks from the database and prints the decisions:
 * {@code java -cp ... dev.romeo.btctradingengine.replay.ReplayRunner 2026-09-15T00:00:00Z 2026-09-16T00:00:00Z}.
 * Only reads the ticks table; nothing is written and no order is sent. Reads the same properties
 * (and .env) as the application, in a small context without the web server or the live pipeline.
 */
public final class ReplayRunner {
    private ReplayRunner() {
    }

    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan("dev.romeo.btctradingengine.config")
    @ImportAutoConfiguration(ValidationAutoConfiguration.class)
    @Import({EngineConfiguration.class, StartupSettingsValidator.class})
    static class ReplayContext {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ReplayRunner <from ISO-8601> <to ISO-8601> [symbol]");
            System.exit(2);
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ReplayContext.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run()) {
            MarketProperties market = context.getBean(MarketProperties.class);
            TradingProperties trading = context.getBean(TradingProperties.class);
            String symbol = args.length > 2 ? args[2] : market.symbol();
            var ticks = new DatabaseTickReader(context.getBean(DataSource.class))
                    .loadTicks(symbol, Instant.parse(args[0]), Instant.parse(args[1]));
            DeterministicReplay.Result result = new DeterministicReplay(new DeterministicReplay.Settings(
                    market.interval(), context.getBean(FeatureProperties.class).largeTradeNotional(),
                    context.getBean(IndicatorPeriods.class), context.getBean(PredictionSettings.class),
                    trading.targetPercent(), trading.stopLossPercent(), trading.allowShort()))
                    .run(new RecordedTicks(ticks));
            System.out.printf("ticks=%d candles=%d predictions=%d closed=%d open=%s%n", result.ticks(), result.candles(),
                    result.predictions().size(), result.closedPositions().size(), result.openPosition().isPresent());
            result.executionLog().forEach(event -> System.out.printf("%s %s %s %s @ %s (%s)%n",
                    event.time(), event.positionId(), event.action(), event.signal(), event.price(), event.value()));
        }
    }
}
