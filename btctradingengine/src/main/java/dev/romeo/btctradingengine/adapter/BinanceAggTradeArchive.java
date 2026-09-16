package dev.romeo.btctradingengine.adapter;

import dev.romeo.btctradingengine.model.AggressorSide;
import dev.romeo.btctradingengine.model.CandleEvent;
import dev.romeo.btctradingengine.model.TradeFlow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipInputStream;

/**
 * Daily spot aggTrades dumps from data.binance.vision, aggregated per candle and per trade size
 * (issue #51), so the size split the live CandleAggregator produces also exists in backtests.
 *
 * Format, checked on BTCUSDT 2026-09-10: no header, columns agg_id, price, quantity, first_id,
 * last_id, timestamp, is_buyer_maker ("True"/"False"), is_best_match. The timestamp is in
 * MICROseconds; older dumps use milliseconds, so the unit is detected from the magnitude. Summing
 * that day's first two minutes reproduced the kline volume and taker buy volume exactly.
 *
 * A dump is ~12 MB compressed and ~800k trades per day, so it is read straight from the download and
 * only the aggregate is cached - about 1,440 rows per day. The aggregate keeps volume per notional
 * bucket rather than a single large/small split, so changing feature.flow.large.trade.notional does
 * not require downloading anything again. A day not published yet (404) is skipped and not cached.
 */
public class BinanceAggTradeArchive {
    /** Lower edges (quote currency) of the notional buckets after the first; bucket 0 starts at 0. */
    public static final List<BigDecimal> BUCKET_EDGES = List.of(
            new BigDecimal("1000"), new BigDecimal("10000"), new BigDecimal("100000"), new BigDecimal("1000000"));
    static final int BUCKETS = BUCKET_EDGES.size() + 1;

    /** A millisecond epoch only reaches 1e14 in the year 5138, so anything above it is microseconds. */
    private static final long MICROSECOND_EPOCH_MIN = 100_000_000_000_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final Path cacheDir;
    private final long intervalMillis;

    public BinanceAggTradeArchive(String baseUrl, Path cacheDir, Duration interval) {
        this.baseUrl = baseUrl;
        this.cacheDir = cacheDir;
        this.intervalMillis = interval.toMillis();
    }

    /** Size buckets per candle open time for the UTC days in [from, to]; days without a dump are skipped. */
    public Map<Instant, SizeBuckets> load(String symbol, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        Map<Instant, SizeBuckets> all = new HashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            all.putAll(loadDay(symbol, day));
        }
        return all;
    }

    private Map<Instant, SizeBuckets> loadDay(String symbol, LocalDate day) throws IOException, InterruptedException {
        // The interval is part of the name: an aggregate built for 1m candles is useless for 5m ones
        Path cached = cacheDir.resolve(symbol + "-aggTrades-" + day + "-" + intervalMillis + "ms.csv");
        if (Files.exists(cached)) {
            return parseAggregate(Files.readString(cached));
        }

        String fileName = symbol + "-aggTrades-" + day + ".zip";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/data/spot/daily/aggTrades/" + symbol + "/" + fileName))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return Map.of();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Binance aggTrades dump " + fileName + " returned HTTP " + response.statusCode());
            }

            Map<Instant, SizeBuckets> aggregate;
            try (ZipInputStream zip = new ZipInputStream(body)) {
                if (zip.getNextEntry() == null) {
                    return Map.of();
                }
                aggregate = aggregate(new InputStreamReader(zip, StandardCharsets.UTF_8), intervalMillis);
            }

            // Written under a temporary name first, so an interrupted run never leaves a partial day cached
            Files.createDirectories(cacheDir);
            Path partial = cacheDir.resolve(cached.getFileName() + ".part");
            Files.writeString(partial, formatAggregate(aggregate));
            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
            return aggregate;
        }
    }

    /** Streams the dump CSV into per-candle size buckets, with the same side mapping the live adapter uses. */
    static Map<Instant, SizeBuckets> aggregate(Reader csv, long intervalMillis) throws IOException {
        Map<Long, BigDecimal[][]> sums = new HashMap<>();
        BufferedReader reader = new BufferedReader(csv);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                continue;   // blank line or a header, which some older dumps have
            }
            String[] columns = line.split(",");
            BigDecimal price = new BigDecimal(columns[1]);
            BigDecimal quantity = new BigDecimal(columns[2]);
            long timeMillis = toEpochMillis(Long.parseLong(columns[5]));
            AggressorSide side = AggressorSide.fromBuyerMaker(Boolean.parseBoolean(columns[6]));

            long openTime = Math.floorDiv(timeMillis, intervalMillis) * intervalMillis;
            BigDecimal[][] candle = sums.computeIfAbsent(openTime, key -> emptySums());
            int sideIndex = side == AggressorSide.BUY ? 0 : 1;
            int bucket = bucketIndex(price.multiply(quantity));
            candle[sideIndex][bucket] = candle[sideIndex][bucket].add(quantity);
        }

        Map<Instant, SizeBuckets> result = new TreeMap<>();
        sums.forEach((openTime, candle) -> result.put(Instant.ofEpochMilli(openTime), SizeBuckets.of(candle)));
        return result;
    }

    static long toEpochMillis(long rawTimestamp) {
        return rawTimestamp >= MICROSECOND_EPOCH_MIN ? rawTimestamp / 1000 : rawTimestamp;
    }

    /** Same inclusive boundary as CandleAggregator: a trade worth exactly an edge belongs to the upper bucket. */
    static int bucketIndex(BigDecimal notional) {
        int bucket = 0;
        for (BigDecimal edge : BUCKET_EDGES) {
            if (notional.compareTo(edge) >= 0) {
                bucket++;
            }
        }
        return bucket;
    }

    /** One line per candle: open time in ms, then the buy volume of each bucket, then the sell volume. */
    static String formatAggregate(Map<Instant, SizeBuckets> aggregate) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(aggregate).forEach((openTime, buckets) -> {
            out.append(openTime.toEpochMilli());
            buckets.buyVolume().forEach(volume -> out.append(',').append(volume.toPlainString()));
            buckets.sellVolume().forEach(volume -> out.append(',').append(volume.toPlainString()));
            out.append('\n');
        });
        return out.toString();
    }

    static Map<Instant, SizeBuckets> parseAggregate(String content) {
        Map<Instant, SizeBuckets> aggregate = new TreeMap<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",");
            BigDecimal[][] candle = emptySums();
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                candle[0][bucket] = new BigDecimal(columns[1 + bucket]);
                candle[1][bucket] = new BigDecimal(columns[1 + BUCKETS + bucket]);
            }
            aggregate.put(Instant.ofEpochMilli(Long.parseLong(columns[0])), SizeBuckets.of(candle));
        }
        return aggregate;
    }

    private static BigDecimal[][] emptySums() {
        BigDecimal[][] sums = new BigDecimal[2][BUCKETS];
        for (BigDecimal[] side : sums) {
            Arrays.fill(side, BigDecimal.ZERO);
        }
        return sums;
    }

    /** Aggressor volume (base units) of one candle, per notional bucket. */
    public record SizeBuckets(List<BigDecimal> buyVolume, List<BigDecimal> sellVolume) {

        static SizeBuckets of(BigDecimal[][] sums) {
            return new SizeBuckets(List.of(sums[0]), List.of(sums[1]));
        }

        /**
         * Large trades are the buckets whose lower edge is at least the threshold. When the threshold
         * is not one of BUCKET_EDGES this effectively rounds it up to the next edge.
         */
        public TradeFlow toTradeFlow(BigDecimal largeTradeNotional) {
            BigDecimal buy = BigDecimal.ZERO;
            BigDecimal sell = BigDecimal.ZERO;
            BigDecimal largeBuy = BigDecimal.ZERO;
            BigDecimal largeSell = BigDecimal.ZERO;
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                buy = buy.add(buyVolume.get(bucket));
                sell = sell.add(sellVolume.get(bucket));
                if (lowerEdge(bucket).compareTo(largeTradeNotional) >= 0) {
                    largeBuy = largeBuy.add(buyVolume.get(bucket));
                    largeSell = largeSell.add(sellVolume.get(bucket));
                }
            }
            return TradeFlow.fromTrades(buy, sell, largeBuy, largeSell);
        }

        private static BigDecimal lowerEdge(int bucket) {
            return bucket == 0 ? BigDecimal.ZERO : BUCKET_EDGES.get(bucket - 1);
        }
    }

    /** How many candles actually got a size split - reported by /backtest, since missing days are skipped. */
    public static long candlesWithSizeSplit(List<CandleEvent> candles) {
        return candles.stream().filter(candle -> candle.flow().hasSizeSplit()).count();
    }
}
