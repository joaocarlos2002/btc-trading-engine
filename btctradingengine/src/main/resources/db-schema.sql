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

-- Index for time-based queries
CREATE INDEX IF NOT EXISTS idx_ticks_time
    ON ticks(time_ms DESC);

-- Optional: partition candles by month for better performance on large datasets
-- Run this after accumulating some data
-- ALTER TABLE candles PARTITION BY RANGE (EXTRACT(EPOCH FROM created_at));
