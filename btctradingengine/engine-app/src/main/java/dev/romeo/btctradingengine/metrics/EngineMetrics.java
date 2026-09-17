package dev.romeo.btctradingengine.metrics;

import dev.romeo.btctradingengine.adapter.FeedStats;
import dev.romeo.btctradingengine.persistence.DatabaseWriter;
import dev.romeo.btctradingengine.persistence.TickRetentionJob;
import dev.romeo.btctradingengine.trading.Position;
import dev.romeo.btctradingengine.trading.PositionManager;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.math.BigDecimal;

/**
 * Meters over the long-lived pipeline beans (issue #104). Every meter reads a counter or field those
 * objects already keep, on scrape: nothing here runs on the tick path.
 */
public class EngineMetrics implements MeterBinder {
    private final FeedStats feed;
    private final DatabaseWriter dbWriter;
    private final TickRetentionJob tickRetention;
    private final PositionManager positions;
    private final BigDecimal maxDrawdownPercent;

    public EngineMetrics(FeedStats feed, DatabaseWriter dbWriter, TickRetentionJob tickRetention,
                         PositionManager positions, BigDecimal maxDrawdownPercent) {
        this.feed = feed;
        this.dbWriter = dbWriter;
        this.tickRetention = tickRetention;
        this.positions = positions;
        this.maxDrawdownPercent = maxDrawdownPercent;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("feed.tick.lag.ms", feed, FeedStats::lastLagMillis)
                .description("Receipt time minus Binance trade time of the latest aggTrade")
                .strongReference(true).register(registry);
        Gauge.builder("feed.tick.age.ms", feed, FeedStats::millisSinceLastTick)
                .description("Milliseconds since the latest aggTrade arrived (since startup before the first)")
                .strongReference(true).register(registry);
        FunctionCounter.builder("feed.ticks.total", feed, FeedStats::ticksTotal)
                .description("aggTrade ticks received from the market data stream")
                .register(registry);

        Gauge.builder("dbwriter.queue.size", dbWriter, DatabaseWriter::queueSize)
                .description("Ticks and candles waiting to be written")
                .strongReference(true).register(registry);
        FunctionCounter.builder("dbwriter.dropped", dbWriter, DatabaseWriter::droppedEvents)
                .description("Ticks and candles dropped because the writer queue was full")
                .register(registry);
        FunctionCounter.builder("ticks.retention.deleted", tickRetention, TickRetentionJob::deletedTotal)
                .description("Ticks deleted by the retention job")
                .register(registry);

        for (PositionManager.EntryBlock block : PositionManager.EntryBlock.values()) {
            FunctionCounter.builder("prediction.entry.blocked", positions, p -> p.entriesBlocked(block))
                    .description("BUY/SELL signals that did not open a position, by reason")
                    .tag("reason", block.tag())
                    .register(registry);
        }
        FunctionCounter.builder("orders.exit.failures", positions, PositionManager::exitFailuresTotal)
                .description("Real exit orders that failed; the position stays open")
                .register(registry);
        Gauge.builder("position.open", positions, p -> p.getOpenPosition().isPresent() ? 1 : 0)
                .description("1 while a position is open (including pending entry/exit)")
                .strongReference(true).register(registry);
        Gauge.builder("position.unrealized.pnl.pct", positions, EngineMetrics::unrealizedPnlPercent)
                .description("Unrealized P&L of the open position in percent; 0 when flat")
                .strongReference(true).register(registry);
        Gauge.builder("portfolio.drawdown.pct", positions, EngineMetrics::drawdownPercent)
                .description("Equity drawdown from the initial capital in percent; NaN in simulation")
                .strongReference(true).register(registry);
        Gauge.builder("portfolio.drawdown.max.pct", maxDrawdownPercent, BigDecimal::doubleValue)
                .description("trading.max.drawdown.percent: new entries stop at this drawdown")
                .strongReference(true).register(registry);
    }

    static double unrealizedPnlPercent(PositionManager positions) {
        return positions.getOpenPosition()
                .map(Position::getPnLPercent)
                .map(BigDecimal::doubleValue)
                .orElse(0.0);
    }

    static double drawdownPercent(PositionManager positions) {
        return positions.getPortfolioManager()
                .map(portfolio -> portfolio.calculateDrawdownPercent().doubleValue())
                .orElse(Double.NaN);
    }
}
