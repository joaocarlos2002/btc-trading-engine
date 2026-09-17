package dev.romeo.btctradingengine.derivatives;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.romeo.btctradingengine.resilience.BinanceCall;
import dev.romeo.btctradingengine.resilience.BinanceEndpoint;
import dev.romeo.btctradingengine.resilience.BinanceResilience;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Public, unauthenticated USD-M futures endpoints. Always mainnet (binance.futures.rest.url): testnet
 * futures data is synthetic, the same reason /backtest reads mainnet klines.
 */
public class BinanceFuturesClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FUNDING_PAGE_LIMIT = 1000;
    private static final BinanceEndpoint FUNDING_RATE = BinanceEndpoint.futures("GET", "/fapi/v1/fundingRate", 1);
    private static final BinanceEndpoint OPEN_INTEREST = BinanceEndpoint.futures("GET", "/fapi/v1/openInterest", 1);
    // Weight 0 in the docs, but capped at 1000 requests per 5 minutes: counted as 1
    private static final BinanceEndpoint LONG_SHORT = BinanceEndpoint.futures("GET",
            "/futures/data/globalLongShortAccountRatio", 1);

    private final BinanceResilience resilience;
    private final String baseUrl;

    public BinanceFuturesClient(BinanceResilience resilience, String baseUrl) {
        this.resilience = resilience;
        this.baseUrl = baseUrl;
    }

    /** Settled funding rates in [from, to], oldest first. */
    public List<TimedValue> fundingRates(String symbol, Instant from, Instant to) throws Exception {
        List<TimedValue> all = new ArrayList<>();
        long start = from.toEpochMilli();
        long end = to.toEpochMilli();
        while (start <= end) {
            List<TimedValue> page = parseFundingRates(get(FUNDING_RATE, BinanceCall.HISTORY,
                    "/fapi/v1/fundingRate?symbol=" + symbol
                    + "&startTime=" + start + "&endTime=" + end + "&limit=" + FUNDING_PAGE_LIMIT));
            all.addAll(page);
            if (page.size() < FUNDING_PAGE_LIMIT) {
                break;
            }
            start = page.get(page.size() - 1).time().toEpochMilli() + 1;
        }
        return all;
    }

    public Optional<TimedValue> latestFundingRate(String symbol) throws Exception {
        List<TimedValue> rates = parseFundingRates(get(FUNDING_RATE, BinanceCall.MARKET_DATA,
                "/fapi/v1/fundingRate?symbol=" + symbol + "&limit=1"));
        return rates.isEmpty() ? Optional.empty() : Optional.of(rates.get(rates.size() - 1));
    }

    public TimedValue openInterest(String symbol) throws Exception {
        return parseOpenInterest(get(OPEN_INTEREST, BinanceCall.MARKET_DATA, "/fapi/v1/openInterest?symbol=" + symbol));
    }

    public Optional<TimedValue> longShortRatio(String symbol) throws Exception {
        List<TimedValue> ratios = parseLongShortRatios(
                get(LONG_SHORT, BinanceCall.MARKET_DATA,
                        "/futures/data/globalLongShortAccountRatio?symbol=" + symbol + "&period=5m&limit=1"));
        return ratios.isEmpty() ? Optional.empty() : Optional.of(ratios.get(ratios.size() - 1));
    }

    /** Latest klines including the one still open, as (open time, close) pairs. */
    public List<TimedValue> latestKlineCloses(String symbol, String interval, int limit) throws Exception {
        return parseKlineCloses(get(BinanceEndpoint.futuresKlines(limit), BinanceCall.MARKET_DATA,
                "/fapi/v1/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit));
    }

    static List<TimedValue> parseFundingRates(String json) throws IOException {
        List<TimedValue> rates = new ArrayList<>();
        for (JsonNode row : MAPPER.readTree(json)) {
            rates.add(new TimedValue(
                    Instant.ofEpochMilli(row.get("fundingTime").asLong()),
                    new BigDecimal(row.get("fundingRate").asText())));
        }
        return rates;
    }

    static TimedValue parseOpenInterest(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        return new TimedValue(
                Instant.ofEpochMilli(node.get("time").asLong()),
                new BigDecimal(node.get("openInterest").asText()));
    }

    /** The timestamp comes as a number or as a numeric string depending on the endpoint; asLong reads both. */
    static List<TimedValue> parseLongShortRatios(String json) throws IOException {
        List<TimedValue> ratios = new ArrayList<>();
        for (JsonNode row : MAPPER.readTree(json)) {
            ratios.add(new TimedValue(
                    Instant.ofEpochMilli(row.get("timestamp").asLong()),
                    new BigDecimal(row.get("longShortRatio").asText())));
        }
        return ratios;
    }

    /** Same row format as spot klines: index 0 = open time, index 4 = close. */
    static List<TimedValue> parseKlineCloses(String json) throws IOException {
        List<TimedValue> closes = new ArrayList<>();
        for (JsonNode row : MAPPER.readTree(json)) {
            closes.add(new TimedValue(
                    Instant.ofEpochMilli(row.get(0).asLong()),
                    new BigDecimal(row.get(4).asText())));
        }
        return closes;
    }

    private String get(BinanceEndpoint endpoint, BinanceCall call, String pathAndQuery) throws Exception {
        HttpResponse<String> response = resilience.send(endpoint, call,
                () -> HttpRequest.newBuilder().uri(URI.create(baseUrl + pathAndQuery)).GET().build());
        if (response.statusCode() != 200) {
            String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
            throw new IllegalStateException("Binance futures " + path + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    public record TimedValue(Instant time, BigDecimal value) {}
}
