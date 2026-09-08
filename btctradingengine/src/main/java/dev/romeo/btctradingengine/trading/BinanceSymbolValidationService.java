package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BinanceSymbolValidationService {
    private static final Logger logger = LoggerFactory.getLogger(BinanceSymbolValidationService.class);

    private final BinanceOrderExecutor orderExecutor;
    private final ConcurrentHashMap<String, BinanceOrderExecutor.SymbolFilters> filterCache = new ConcurrentHashMap<>();

    public BinanceSymbolValidationService(BinanceOrderExecutor orderExecutor) {
        this.orderExecutor = orderExecutor;
    }

    public Optional<BigDecimal> validateAndAdjustQuantity(String symbol, BigDecimal quantity, BigDecimal price) {
        BinanceOrderExecutor.SymbolFilters filters = getOrCacheFilters(symbol);
        if (filters == null) {
            logger.error("âœ— Could not fetch filters for {}, rejecting order", symbol);
            return Optional.empty();
        }

        return validateQuantity(filters, quantity, price);
    }

    private Optional<BigDecimal> validateQuantity(BinanceOrderExecutor.SymbolFilters filters,
                                                   BigDecimal quantity, BigDecimal price) {
        // 1. Validate minimum quantity (LOT_SIZE)
        if (quantity.compareTo(filters.minQty()) < 0) {
            logger.warn("âš  Quantity {} is below minimum {}", quantity, filters.minQty());
            quantity = filters.minQty();
        }

        // 2. Validate maximum quantity (LOT_SIZE)
        if (quantity.compareTo(filters.maxQty()) > 0) {
            logger.warn("âš  Quantity {} exceeds maximum {}, reducing", quantity, filters.maxQty());
            quantity = filters.maxQty();
        }

        // 3. Adjust to step size
        BigDecimal adjustedQty = adjustToStepSize(quantity, filters.stepSize());
        if (!adjustedQty.equals(quantity)) {
            logger.warn("âš  Adjusted quantity {} to step size {} â†’ {}", quantity, filters.stepSize(), adjustedQty);
            quantity = adjustedQty;
        }

        // 4. Validate minimum notional (price * quantity >= MIN_NOTIONAL)
        BigDecimal notional = quantity.multiply(price);
        if (notional.compareTo(filters.minNotional()) < 0) {
            logger.warn("âš  Notional {} is below minimum {}. Increasing quantity.",
                    notional, filters.minNotional());
            quantity = filters.minNotional()
                    .divide(price, 8, RoundingMode.UP)
                    .min(filters.maxQty());
            quantity = adjustToStepSize(quantity, filters.stepSize());

            notional = quantity.multiply(price);
            if (notional.compareTo(filters.minNotional()) < 0) {
                logger.error("âœ— Cannot meet minimum notional {} with max quantity {}",
                        filters.minNotional(), filters.maxQty());
                return Optional.empty();
            }
        }

        logger.info("âœ“ Quantity validated and adjusted: {} (notional: {})", quantity, notional);
        return Optional.of(quantity);
    }

    private BigDecimal adjustToStepSize(BigDecimal quantity, BigDecimal stepSize) {
        if (stepSize.compareTo(BigDecimal.ZERO) == 0) {
            return quantity;
        }
        BigDecimal stepCount = quantity.divide(stepSize, 0, RoundingMode.DOWN);
        return stepCount.multiply(stepSize);
    }

    private BinanceOrderExecutor.SymbolFilters getOrCacheFilters(String symbol) {
        return filterCache.computeIfAbsent(symbol, s -> {
            logger.info("Fetching filters for {}", s);
            BinanceOrderExecutor.SymbolFilters filters = orderExecutor.getSymbolFilters(s);
            if (filters != null) {
                logger.info("Cached filters for {}: minNotional={}, stepSize={}",
                        s, filters.minNotional(), filters.stepSize());
            }
            return filters;
        });
    }

    public void invalidateCache(String symbol) {
        filterCache.remove(symbol);
        logger.debug("Invalidated filter cache for {}", symbol);
    }
}

