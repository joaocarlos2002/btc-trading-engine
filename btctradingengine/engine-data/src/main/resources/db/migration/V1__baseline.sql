-- Baseline (issue #102): the schema the engine created at startup before Flyway - db-schema.sql run by
-- DatabaseInitializer, plus the DDL that TradeJournal and JdbcOrderCommandStore executed. Columns that
-- used to be added with ALTER TABLE ... ADD COLUMN IF NOT EXISTS are folded in at the end of their
-- table, in the same order, so a fresh database has the same column order as an upgraded one.
--
-- A database created before Flyway already has all of this: spring.flyway.baseline-on-migrate=true with
-- baseline-version=1 records it as V1 without running this file. Never edit this file once released;
-- schema changes go in V2__..., V3__... next to it.

-- Candlesticks aggregated from ticks
CREATE TABLE candles (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    open_time_ms BIGINT NOT NULL,
    close_time_ms BIGINT NOT NULL,
    open NUMERIC(20, 8) NOT NULL,
    high NUMERIC(20, 8) NOT NULL,
    low NUMERIC(20, 8) NOT NULL,
    close NUMERIC(20, 8) NOT NULL,
    volume NUMERIC(20, 8) NOT NULL,
    tick_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    -- Aggressor flow (issue #11). NULL flow_source = no flow data. Taker sell volume is
    -- volume - taker_buy_volume; large_* are only set when flow_source = 'TRADES'.
    taker_buy_volume NUMERIC(20, 8),
    large_buy_volume NUMERIC(20, 8),
    large_sell_volume NUMERIC(20, 8),
    flow_source VARCHAR(10)
);

CREATE INDEX idx_candles_symbol_time ON candles(symbol, close_time_ms DESC);
CREATE INDEX idx_candles_close_time ON candles(close_time_ms DESC);
-- One candle per (symbol, open_time_ms); target of ON CONFLICT in DatabaseWriter
CREATE UNIQUE INDEX uq_candles_symbol_open_time ON candles(symbol, open_time_ms);

-- Raw ticks
CREATE TABLE ticks (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    time_ms BIGINT NOT NULL,
    price NUMERIC(20, 8) NOT NULL,
    quantity NUMERIC(20, 8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    -- BUY/SELL, NULL when unknown; kept so the large-trade threshold can be re-applied to past ticks
    aggressor_side VARCHAR(7)
);

CREATE INDEX idx_ticks_symbol_time ON ticks(symbol, time_ms DESC);
-- Also what TickRetentionJob's batched deletes walk (issue #85)
CREATE INDEX idx_ticks_time ON ticks(time_ms DESC);

-- Derivatives readings polled from USD-M futures (issue #53). time_ms is when the bot received the
-- reading; a column is NULL when that call failed in the poll.
CREATE TABLE derivatives_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    time_ms BIGINT NOT NULL,
    open_interest NUMERIC(28, 8),
    long_short_ratio NUMERIC(20, 8),
    funding_rate NUMERIC(20, 10),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_derivatives_symbol_time ON derivatives_snapshots(symbol, time_ms DESC);

-- Order book snapshots polled from the spot depth endpoint (issue #10)
CREATE TABLE order_book_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    time_ms BIGINT NOT NULL,
    levels INTEGER NOT NULL,
    bid_volume NUMERIC(28, 8) NOT NULL,
    ask_volume NUMERIC(28, 8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_order_book_symbol_time ON order_book_snapshots(symbol, time_ms DESC);

-- Trade journal (TradeJournal)
CREATE TABLE trades (
    id BIGSERIAL PRIMARY KEY,
    position_id VARCHAR(50) NOT NULL UNIQUE,
    symbol VARCHAR(20) NOT NULL,
    signal VARCHAR(10) NOT NULL,
    entry_price NUMERIC(20, 8) NOT NULL,
    entry_time TIMESTAMP WITH TIME ZONE NOT NULL,
    exit_price NUMERIC(20, 8),
    exit_time TIMESTAMP WITH TIME ZONE,
    exit_reason VARCHAR(50),
    pnl NUMERIC(20, 8),
    pnl_percent NUMERIC(10, 6),
    target_percent NUMERIC(10, 6) NOT NULL,
    stop_loss_percent NUMERIC(10, 6) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    quantity NUMERIC(20, 8),
    state VARCHAR(20)
);

CREATE INDEX idx_trades_symbol ON trades(symbol);
CREATE INDEX idx_trades_entry_time ON trades(entry_time DESC);
CREATE INDEX idx_trades_status ON trades(exit_time);

-- Position lifecycle events (TradeJournal.recordExecutionLog)
CREATE TABLE execution_log (
    id BIGSERIAL PRIMARY KEY,
    position_id VARCHAR(50) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    signal VARCHAR(10),
    price NUMERIC(20, 8) NOT NULL,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
    value NUMERIC(20, 8),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_exec_position ON execution_log(position_id);
CREATE INDEX idx_exec_time ON execution_log(event_time DESC);

-- Order outbox (issue #111, JdbcOrderCommandStore)
CREATE TABLE order_commands (
    id BIGSERIAL PRIMARY KEY,
    client_order_id VARCHAR(64) NOT NULL UNIQUE,
    position_id VARCHAR(50) NOT NULL,
    type VARCHAR(10) NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    status VARCHAR(10) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT
);

CREATE INDEX idx_order_commands_unresolved ON order_commands(status) WHERE status IN ('PENDING', 'SENT');
CREATE INDEX idx_order_commands_position ON order_commands(position_id);
