package dev.romeo.btctradingengine.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BinanceSymbolValidationService {
    private static final Logger logger = LoggerFactory.getLogger(BinanceSymbolValidationService.class);

    private final ExecutionPort orderExecutor;
    private final ConcurrentHashMap<String, BinanceOrderExecutor.SymbolFilters> filterCache = new ConcurrentHashMap<>();

    public BinanceSymbolValidationService(ExecutionPort orderExecutor) {
        this.orderExecutor = orderExecutor;
    }

    /** Never raises the order above the notional originally requested (quantity * price). */
    public Optional<BigDecimal> validateAndAdjustQuantity(String symbol, BigDecimal quantity, BigDecimal price) {
        return validateAndAdjustQuantity(symbol, quantity, price, quantity.multiply(price));
    }

    /**
     * Fits the quantity to LOT_SIZE and MIN_NOTIONAL/NOTIONAL. It may round up to the exchange
     * minimum, but rejects the order when that would cost more than {@code maxNotional}
     * (the capital allocated to the position) instead of silently spending more.
     */
    public Optional<BigDecimal> validateAndAdjustQuantity(String symbol, BigDecimal quantity, BigDecimal price,
                                                          BigDecimal maxNotional) {
        BinanceOrderExecutor.SymbolFilters filters = getOrCacheFilters(symbol);
        if (filters == null) {
            logger.error("Could not fetch filters for {}, rejecting order", symbol);
            return Optional.empty();
        }
        if (price == null || price.signum() <= 0) {
            logger.error("Invalid price {} for {}, rejecting order", price, symbol);
            return Optional.empty();
        }

        return validateQuantity(filters, quantity, price, maxNotional);
    }

    private Optional<BigDecimal> validateQuantity(BinanceOrderExecutor.SymbolFilters filters,
                                                   BigDecimal quantity, BigDecimal price, BigDecimal maxNotional) {
        BigDecimal step = filters.stepSize();
        BigDecimal adjusted = floorToStep(quantity, step);

        // maxQty 0 means the LOT_SIZE filter was absent
        if (filters.maxQty().signum() > 0 && adjusted.compareTo(filters.maxQty()) > 0) {
            logger.warn("Quantity {} exceeds maximum {}, reducing", adjusted, filters.maxQty());
            adjusted = floorToStep(filters.maxQty(), step);
        }

        BigDecimal minByNotional = filters.minNotional().signum() > 0
                ? filters.minNotional().divide(price, 8, RoundingMode.UP) : BigDecimal.ZERO;
        BigDecimal required = ceilToStep(filters.minQty().max(minByNotional), step);

        if (adjusted.compareTo(required) < 0) {
            BigDecimal requiredNotional = required.multiply(price);
            if (maxNotional == null || requiredNotional.compareTo(maxNotional) > 0) {
                logger.error("Order rejected: exchange minimum qty {} (notional {}) exceeds allocated {}",
                        required, requiredNotional, maxNotional);
                return Optional.empty();
            }
            logger.warn("Quantity {} below exchange minimum, raising to {} (within allocated {})",
                    adjusted, required, maxNotional);
            adjusted = required;
        }

        if (adjusted.signum() <= 0
                || (filters.maxQty().signum() > 0 && adjusted.compareTo(filters.maxQty()) > 0)) {
            logger.error("Cannot fit quantity {} into LOT_SIZE {}..{}", adjusted, filters.minQty(), filters.maxQty());
            return Optional.empty();
        }

        logger.info("Quantity validated and adjusted: {} (notional: {})", adjusted, adjusted.multiply(price));
        return Optional.of(adjusted);
    }

    private BigDecimal floorToStep(BigDecimal quantity, BigDecimal stepSize) {
        if (stepSize.signum() == 0) {
            return quantity;
        }
        return quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
    }

    private BigDecimal ceilToStep(BigDecimal quantity, BigDecimal stepSize) {
        if (stepSize.signum() == 0) {
            return quantity;
        }
        return quantity.divide(stepSize, 0, RoundingMode.UP).multiply(stepSize);
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

