package dev.romeo.btctradingengine.persistence;

import java.math.BigDecimal;
import java.time.Instant;

public class PersistedCandle {
    private Long id;
    private String symbol;
    private Long openTimeMs;
    private Long closeTimeMs;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
    private Integer tickCount;
    private Instant createdAt;

    public PersistedCandle() {}

    public PersistedCandle(String symbol, Long openTimeMs, Long closeTimeMs,
                         BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, BigDecimal volume, Integer tickCount) {
        this.symbol = symbol;
        this.openTimeMs = openTimeMs;
        this.closeTimeMs = closeTimeMs;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.tickCount = tickCount;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Long getOpenTimeMs() { return openTimeMs; }
    public void setOpenTimeMs(Long openTimeMs) { this.openTimeMs = openTimeMs; }

    public Long getCloseTimeMs() { return closeTimeMs; }
    public void setCloseTimeMs(Long closeTimeMs) { this.closeTimeMs = closeTimeMs; }

    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }

    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }

    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }

    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }

    public BigDecimal getVolume() { return volume; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }

    public Integer getTickCount() { return tickCount; }
    public void setTickCount(Integer tickCount) { this.tickCount = tickCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

