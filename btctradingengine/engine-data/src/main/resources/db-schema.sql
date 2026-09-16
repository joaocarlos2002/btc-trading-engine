-- Create candles table for storing aggregated candlestick data
CREATE TABLE IF NOT EXISTS candles (
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
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast lookups by symbol and time
CREATE INDEX IF NOT EXISTS idx_candles_symbol_time
    ON candles(symbol, close_time_ms DESC);

-- Index for time-based queries
CREATE INDEX IF NOT EXISTS idx_candles_close_time
    ON candles(close_time_ms DESC);

-- One candle per (symbol, open_time_ms); target of ON CONFLICT in DatabaseWriter.
-- A unique index (not ADD CONSTRAINT) keeps this idempotent without a DO block,
-- which DatabaseInitializer cannot run because it splits statements on semicolons.
-- If this fails at startup, duplicates already exist. Inspect them with:
--   SELECT symbol, open_time_ms, COUNT(*) FROM candles GROUP BY symbol, open_time_ms HAVING COUNT(*) > 1
-- and, once reviewed, keep the oldest row of each group with:
--   DELETE FROM candles a USING candles b WHERE a.symbol = b.symbol AND a.open_time_ms = b.open_time_ms AND a.id > b.id
CREATE UNIQUE INDEX IF NOT EXISTS uq_candles_symbol_open_time
    ON candles(symbol, open_time_ms);

-- Aggressor flow (issue #11). Nullable: rows written before these columns existed, and candles
-- whose source reports no aggressor side, have flow_source NULL (no flow data).
-- Taker sell volume is volume - taker_buy_volume. large_* are only set when flow_source = 'TRADES'.
ALTER TABLE candles ADD COLUMN IF NOT EXISTS taker_buy_volume NUMERIC(20, 8);
ALTER TABLE candles ADD COLUMN IF NOT EXISTS large_buy_volume NUMERIC(20, 8);
ALTER TABLE candles ADD COLUMN IF NOT EXISTS large_sell_volume NUMERIC(20, 8);
ALTER TABLE candles ADD COLUMN IF NOT EXISTS flow_source VARCHAR(10);

-- Create ticks table for storing raw price data
CREATE TABLE IF NOT EXISTS ticks (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    time_ms BIGINT NOT NULL,
    price NUMERIC(20, 8) NOT NULL,
    quantity NUMERIC(20, 8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast lookups by symbol and time
CREATE INDEX IF NOT EXISTS idx_ticks_symbol_time
    ON ticks(symbol, time_ms DESC);

-- Index for time-based queries; also what TickRetentionJob's batched deletes walk (issue #85)
CREATE INDEX IF NOT EXISTS idx_ticks_time
    ON ticks(time_ms DESC);

-- Raw aggressor side per tick (BUY/SELL, NULL when unknown). Kept so the heuristic large-trade
-- threshold can be re-applied to past ticks instead of being frozen into the candles.
ALTER TABLE ticks ADD COLUMN IF NOT EXISTS aggressor_side VARCHAR(7);

-- Derivatives readings polled from USD-M futures (issue #53). time_ms is when the bot received the
-- reading, not Binance's period timestamp, so replaying this table never attaches a value to a
-- candle that closed before it was known. A column is NULL when that call failed in the poll.
CREATE TABLE IF NOT EXISTS derivatives_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    time_ms BIGINT NOT NULL,
    open_interest NUMERIC(28, 8),
    long_short_ratio NUMERIC(20, 8),
    funding_rate NUMERIC(20, 10),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_derivatives_symbol_time
    ON derivatives_snapshots(symbol, time_ms DESC);

-- Order book snapshots polled from the spot depth endpoint (issue #10). Binance has no free order
-- book history, so these rows are what order book features can eventually be backtested against.
CREATE TABLE IF NOT EXISTS order_book_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    time_ms BIGINT NOT NULL,
    levels INTEGER NOT NULL,
    bid_volume NUMERIC(28, 8) NOT NULL,
    ask_volume NUMERIC(28, 8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_book_symbol_time
    ON order_book_snapshots(symbol, time_ms DESC);

-- Optional: partition candles by month for better performance on large datasets
-- Run this after accumulating some data
-- ALTER TABLE candles PARTITION BY RANGE (EXTRACT(EPOCH FROM created_at));
