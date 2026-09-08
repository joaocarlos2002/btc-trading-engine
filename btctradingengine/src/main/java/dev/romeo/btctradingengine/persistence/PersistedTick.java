package dev.romeo.btctradingengine.persistence;

import java.math.BigDecimal;
import java.time.Instant;

public class PersistedTick {
    private Long id;
    private String symbol;
    private Long timeMs;
    private BigDecimal price;
    private BigDecimal quantity;
    private Instant createdAt;

    public PersistedTick() {}

    public PersistedTick(String symbol, Long timeMs, BigDecimal price, BigDecimal quantity) {
        this.symbol = symbol;
        this.timeMs = timeMs;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Long getTimeMs() { return timeMs; }
    public void setTimeMs(Long timeMs) { this.timeMs = timeMs; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

