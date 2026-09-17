package dev.romeo.btctradingengine.trading;

import java.util.Map;

/**
 * Binance Spot REST and User Data Stream payloads in the exact shape the API returns them (field
 * names, string-encoded decimals, extra fields the bot ignores), for the WireMock integration tests
 * (issue #103). {@code ${name}} placeholders are filled by {@link #fill}.
 */
final class BinanceFixtures {
    private BinanceFixtures() {}

    static String fill(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        if (result.contains("${")) {
            throw new IllegalArgumentException("unfilled placeholder in " + result);
        }
        return result;
    }

    /**
     * POST /api/v3/order, newOrderRespType=FULL, MARKET BUY filled in two trades: avg 499.8833 /
     * 0.00833 = 60010.
     */
    static final String ORDER_BUY_FILLED =
            """
            {"symbol":"BTCUSDT","orderId":4281765,"orderListId":-1,"clientOrderId":"${clientOrderId}",\
            "transactTime":1789567200123,"price":"0.00000000","origQty":"0.00833000","executedQty":"0.00833000",\
            "origQuoteOrderQty":"0.00000000","cummulativeQuoteQty":"499.88330000","status":"FILLED",\
            "timeInForce":"GTC","type":"MARKET","side":"BUY","workingTime":1789567200123,\
            "fills":[{"price":"60008.00000000","qty":"0.00500000","commission":"0.00000000","commissionAsset":"BTC","tradeId":913551},\
            {"price":"60013.00000000","qty":"0.00333000","commission":"0.00000000","commissionAsset":"BTC","tradeId":913552}],\
            "selfTradePreventionMode":"EXPIRE_MAKER"}""";

    /** POST /api/v3/order answered before the match: accepted, nothing executed yet. */
    static final String ORDER_BUY_NEW =
            """
            {"symbol":"BTCUSDT","orderId":4281766,"orderListId":-1,"clientOrderId":"${clientOrderId}",\
            "transactTime":1789567200456,"price":"0.00000000","origQty":"0.00833000","executedQty":"0.00000000",\
            "origQuoteOrderQty":"0.00000000","cummulativeQuoteQty":"0.00000000","status":"NEW","timeInForce":"GTC",\
            "type":"MARKET","side":"BUY","workingTime":1789567200456,"fills":[],"selfTradePreventionMode":"EXPIRE_MAKER"}""";

    /**
     * GET /api/v3/order of a filled MARKET order; {@code side}, {@code quote} and {@code orderId}
     * vary.
     */
    static final String QUERY_ORDER_FILLED =
            """
            {"symbol":"BTCUSDT","orderId":${orderId},"orderListId":-1,"clientOrderId":"${clientOrderId}",\
            "price":"0.00000000","origQty":"0.00833000","executedQty":"0.00833000","cummulativeQuoteQty":"${quote}",\
            "status":"FILLED","timeInForce":"GTC","type":"MARKET","side":"${side}","stopPrice":"0.00000000",\
            "icebergQty":"0.00000000","time":1789567200456,"updateTime":1789567200460,"isWorking":true,\
            "workingTime":1789567200456,"origQuoteOrderQty":"0.00000000","selfTradePreventionMode":"EXPIRE_MAKER"}""";

    static final String EXCHANGE_INFO_BTCUSDT =
            """
            {"timezone":"UTC","serverTime":1789567200000,"rateLimits":[{"rateLimitType":"REQUEST_WEIGHT","interval":"MINUTE",\
            "intervalNum":1,"limit":6000},{"rateLimitType":"ORDERS","interval":"SECOND","intervalNum":10,"limit":100}],\
            "exchangeFilters":[],"symbols":[{"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC","baseAssetPrecision":8,\
            "quoteAsset":"USDT","quotePrecision":8,"quoteAssetPrecision":8,\
            "orderTypes":["LIMIT","LIMIT_MAKER","MARKET","STOP_LOSS_LIMIT","TAKE_PROFIT_LIMIT"],"icebergAllowed":true,\
            "ocoAllowed":true,"otoAllowed":true,"quoteOrderQtyMarketAllowed":true,"allowTrailingStop":true,\
            "cancelReplaceAllowed":true,"isSpotTradingAllowed":true,"isMarginTradingAllowed":true,"filters":[\
            {"filterType":"PRICE_FILTER","minPrice":"0.01000000","maxPrice":"1000000.00000000","tickSize":"0.01000000"},\
            {"filterType":"LOT_SIZE","minQty":"0.00001000","maxQty":"9000.00000000","stepSize":"0.00001000"},\
            {"filterType":"ICEBERG_PARTS","limit":10},\
            {"filterType":"MARKET_LOT_SIZE","minQty":"0.00000000","maxQty":"83.76572430","stepSize":"0.00000000"},\
            {"filterType":"TRAILING_DELTA","minTrailingAboveDelta":10,"maxTrailingAboveDelta":2000,"minTrailingBelowDelta":10,"maxTrailingBelowDelta":2000},\
            {"filterType":"PERCENT_PRICE_BY_SIDE","bidMultiplierUp":"5","bidMultiplierDown":"0.2","askMultiplierUp":"5","askMultiplierDown":"0.2","avgPriceMins":5},\
            {"filterType":"NOTIONAL","minNotional":"5.00000000","applyMinToMarket":true,"maxNotional":"9000000.00000000","applyMaxToMarket":false,"avgPriceMins":5},\
            {"filterType":"MAX_NUM_ORDERS","maxNumOrders":200},{"filterType":"MAX_NUM_ALGO_ORDERS","maxNumAlgoOrders":5}],\
            "permissions":[],"permissionSets":[["SPOT","MARGIN"]],"defaultSelfTradePreventionMode":"EXPIRE_MAKER",\
            "allowedSelfTradePreventionModes":["EXPIRE_TAKER","EXPIRE_MAKER","EXPIRE_BOTH"]}]}""";

    /** GET /api/v3/account with {@code btc} and {@code usdt} free balances. */
    static final String ACCOUNT =
            """
            {"makerCommission":10,"takerCommission":10,"buyerCommission":0,"sellerCommission":0,\
            "commissionRates":{"maker":"0.00100000","taker":"0.00100000","buyer":"0.00000000","seller":"0.00000000"},\
            "canTrade":true,"canWithdraw":false,"canDeposit":false,"brokered":false,"requireSelfTradePrevention":false,\
            "preventSor":false,"updateTime":1789567000000,"accountType":"SPOT","balances":[\
            {"asset":"BNB","free":"0.00000000","locked":"0.00000000"},\
            {"asset":"BTC","free":"${btc}","locked":"0.00000000"},\
            {"asset":"USDT","free":"${usdt}","locked":"0.00000000"}],"permissions":["SPOT"],"uid":354937868}""";

    /** POST /api/v3/orderList/oco and GET /api/v3/orderList of a working OCO. */
    static final String ORDER_LIST_EXECUTING =
            """
            {"orderListId":1929,"contingencyType":"OCO","listStatusType":"EXEC_STARTED","listOrderStatus":"EXECUTING",\
            "listClientOrderId":"${listClientOrderId}","transactionTime":1789567200900,"symbol":"BTCUSDT",\
            "orders":[{"symbol":"BTCUSDT","orderId":4281770,"clientOrderId":"x-sl"},\
            {"symbol":"BTCUSDT","orderId":4281771,"clientOrderId":"x-tp"}]}""";

    /** GET /api/v3/openOrders with one bot order and one placed by hand. */
    static final String OPEN_ORDERS =
            """
            [{"symbol":"BTCUSDT","orderId":4281770,"orderListId":1929,"clientOrderId":"btce-BTCUSDT-POS_9-sl",\
            "price":"59000.00000000","origQty":"0.00833000","executedQty":"0.00000000","cummulativeQuoteQty":"0.00000000",\
            "status":"NEW","timeInForce":"GTC","type":"STOP_LOSS_LIMIT","side":"SELL","stopPrice":"59100.00000000",\
            "icebergQty":"0.00000000","time":1789567200900,"updateTime":1789567200900,"isWorking":false,"workingTime":-1,\
            "origQuoteOrderQty":"0.00000000","selfTradePreventionMode":"EXPIRE_MAKER"},\
            {"symbol":"BTCUSDT","orderId":4281999,"orderListId":-1,"clientOrderId":"web_7f3a2b","price":"50000.00000000",\
            "origQty":"0.00100000","executedQty":"0.00000000","cummulativeQuoteQty":"0.00000000","status":"NEW",\
            "timeInForce":"GTC","type":"LIMIT","side":"BUY","stopPrice":"0.00000000","icebergQty":"0.00000000",\
            "time":1789567100000,"updateTime":1789567100000,"isWorking":true,"workingTime":1789567100000,\
            "origQuoteOrderQty":"0.00000000","selfTradePreventionMode":"EXPIRE_MAKER"}]""";

    static final String ERROR_INSUFFICIENT_BALANCE =
            """
            {"code":-2010,"msg":"Account has insufficient balance for requested action."}""";
    static final String ERROR_TIMESTAMP =
            """
            {"code":-1021,"msg":"Timestamp for this request was 1000ms ahead of the server's time."}""";
    static final String ERROR_ORDER_DOES_NOT_EXIST =
            """
            {"code":-2013,"msg":"Order does not exist."}""";
    static final String ERROR_UNKNOWN_ORDER =
            """
            {"code":-2011,"msg":"Unknown order sent."}""";
    static final String ERROR_TOO_MANY_REQUESTS =
            """
            {"code":-1003,"msg":"Too many requests; current limit of IP(203.0.113.7) is 6000 requests per minute."}""";
    static final String ERROR_IP_BANNED =
            """
            {"code":-1003,"msg":"Way too much request weight used; IP banned until 1789567260000."}""";
    static final String ERROR_INTERNAL =
            """
            {"code":-1000,"msg":"An unknown error occurred while processing the request."}""";

    /** User Data Stream (WebSocket API) executionReport event of a MARKET order. */
    static final String EXECUTION_REPORT =
            """
            {"subscriptionId":0,"event":{"e":"executionReport","E":1789567200130,"s":"BTCUSDT","c":"${clientOrderId}",\
            "S":"${side}","o":"MARKET","f":"GTC","q":"0.00833000","p":"0.00000000","P":"0.00000000","F":"0.00000000",\
            "g":-1,"C":"","x":"TRADE","X":"${status}","r":"NONE","i":${orderId},"l":"${lastQty}","z":"${cumQty}",\
            "L":"${lastPrice}","n":"0.00000000","N":"BTC","T":1789567200125,"t":913552,"v":0,"I":9923451,"w":false,\
            "m":false,"M":true,"O":1789567200123,"Z":"499.88330000","Y":"199.82829000","Q":"0.00000000",\
            "W":1789567200123,"V":"EXPIRE_MAKER"}}""";
}
