package dev.romeo.btctradingengine.trading;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * What the trading logic needs from an exchange (issue #111). {@link BinanceOrderExecutor} is the
 * Binance SPOT adapter; the result records still live there, so swapping exchanges means mapping
 * into them rather than rewriting the position logic.
 */
public interface ExecutionPort {
    BinanceOrderExecutor.OrderResult executeBuyMarket(String symbol, BigDecimal quantity, String clientOrderId);

    BinanceOrderExecutor.OrderResult executeSellMarket(String symbol, BigDecimal quantity, String clientOrderId);

    BinanceOrderExecutor.BalanceResult getBalance(String asset);

    BinanceOrderExecutor.SymbolFilters getSymbolFilters(String symbol);

    Optional<BinanceOrderExecutor.QueriedOrder> queryOrder(String symbol, String clientOrderId);

    BinanceOrderExecutor.OrderLookup lookupOrder(String symbol, String clientOrderId);

    Optional<BinanceOrderExecutor.OrderResult> findOrderByClientOrderId(String symbol, String clientOrderId);

    List<BinanceOrderExecutor.OpenOrder> getOpenOrders(String symbol);

    boolean cancelOrder(String symbol, String orderId);

    BinanceOrderExecutor.OcoResult placeOcoSell(String symbol, BigDecimal quantity, BigDecimal targetPrice,
                                                BigDecimal stopPrice, BigDecimal stopLimitPrice,
                                                String listClientOrderId, String targetClientOrderId,
                                                String stopClientOrderId);

    BinanceOrderExecutor.OrderListQuery queryOrderList(String listClientOrderId);

    BinanceOrderExecutor.OrderListQuery cancelOrderList(String symbol, String listClientOrderId);
}
