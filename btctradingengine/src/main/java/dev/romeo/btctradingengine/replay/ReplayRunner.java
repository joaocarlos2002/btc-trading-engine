package dev.romeo.btctradingengine.replay;

import dev.romeo.btctradingengine.config.Config;
import dev.romeo.btctradingengine.persistence.DataSourceManager;
import dev.romeo.btctradingengine.persistence.DatabaseTickReader;

import java.time.Instant;

/**
 * Replays recorded ticks from the database and prints the decisions:
 * {@code java -cp ... dev.romeo.btctradingengine.replay.ReplayRunner 2026-09-15T00:00:00Z 2026-09-16T00:00:00Z}.
 * Only reads the ticks table; nothing is written and no order is sent.
 */
public final class ReplayRunner {
    private ReplayRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ReplayRunner <from ISO-8601> <to ISO-8601> [symbol]");
            System.exit(2);
        }
        Config.validate();
        String symbol = args.length > 2 ? args[2] : Config.getMarketSymbol();
        try {
            var ticks = new DatabaseTickReader(DataSourceManager.getDataSource()).loadTicks(symbol, Instant.parse(args[0]), Instant.parse(args[1]));
            DeterministicReplay.Result result = new DeterministicReplay(new DeterministicReplay.Settings(
                    Config.getMarketInterval(), Config.getLargeTradeNotional(), Config.indicatorPeriods(),
                    Config.predictionSettings(), Config.getTradingTargetPercent(), Config.getTradingStopLossPercent(),
                    Config.isShortSellingAllowed()))
                    .run(new RecordedTicks(ticks));
            System.out.printf("ticks=%d candles=%d predictions=%d closed=%d open=%s%n", result.ticks(), result.candles(),
                    result.predictions().size(), result.closedPositions().size(), result.openPosition().isPresent());
            result.executionLog().forEach(event -> System.out.printf("%s %s %s %s @ %s (%s)%n",
                    event.time(), event.positionId(), event.action(), event.signal(), event.price(), event.value()));
        } finally {
            DataSourceManager.close();
        }
    }
}
