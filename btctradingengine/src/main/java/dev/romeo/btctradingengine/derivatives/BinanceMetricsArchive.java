package dev.romeo.btctradingengine.derivatives;

import dev.romeo.btctradingengine.config.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

/**
 * Daily USD-M futures "metrics" dumps from data.binance.vision (issue #54): open interest and
 * long/short ratios every 5 minutes with years of history, where the REST API keeps only 30 days.
 *
 * Timing, checked against the REST API for 2026-09-10: a row's create_time is the START of its
 * 5-minute period - its values are the ones openInterestHist and globalLongShortAccountRatio publish
 * with timestamp create_time + 5 minutes. Rows are keyed by that later time; using create_time would
 * hand every value to the backtest 5 minutes before it existed.
 *
 * Columns: count_long_short_ratio matches globalLongShortAccountRatio (what the live poller reads),
 * sum_open_interest matches openInterestHist.sumOpenInterest. The live poller reads the instantaneous
 * /fapi/v1/openInterest instead, so live and backtest open interest can differ slightly. Rows are not
 * sorted inside the file.
 *
 * Zips are cached on disk, so each day is downloaded once. A day not published yet (the dump lags
 * about a day) comes back 404 and is skipped without being cached, so a later run picks it up.
 */
public class BinanceMetricsArchive {
    /** Offset from create_time to the moment the row's values are published. */
    static final Duration PERIOD = Duration.ofMinutes(5);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter CREATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final Path cacheDir;

    public BinanceMetricsArchive() {
        this(Config.getBinanceDataUrl(), Config.getMetricsCacheDir());
    }

    public BinanceMetricsArchive(String baseUrl, Path cacheDir) {
        this.baseUrl = baseUrl;
        this.cacheDir = cacheDir;
    }

    /** Every published row for the UTC days in [from, to]; days without a dump are skipped. */
    public List<MetricsRow> load(String symbol, LocalDate from, LocalDate to) throws IOException, InterruptedException {
        List<MetricsRow> rows = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byte[] zip = dailyZip(symbol, day);
            if (zip != null) {
                rows.addAll(parseZip(zip));
            }
        }
        return rows;
    }

    private byte[] dailyZip(String symbol, LocalDate day) throws IOException, InterruptedException {
        String fileName = symbol + "-metrics-" + day + ".zip";
        Path cached = cacheDir.resolve(fileName);
        if (Files.exists(cached)) {
            return Files.readAllBytes(cached);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/data/futures/um/daily/metrics/" + symbol + "/" + fileName))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Binance metrics dump " + fileName + " returned HTTP " + response.statusCode());
        }

        // Write to a temporary name first, so an interrupted download never leaves a truncated zip in the cache
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(fileName + ".part");
        Files.write(partial, response.body());
        Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
        return response.body();
    }

    static List<MetricsRow> parseZip(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            if (in.getNextEntry() == null) {
                return List.of();
            }
            return parseCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Columns are looked up by header name, so a column added or reordered upstream does not shift the values. */
    static List<MetricsRow> parseCsv(String csv) {
        String[] lines = csv.split("\\R");
        if (lines.length == 0) {
            return List.of();
        }
        List<String> header = List.of(lines[0].trim().split(","));
        int timeColumn = requireColumn(header, "create_time");
        int openInterestColumn = requireColumn(header, "sum_open_interest");
        int longShortColumn = requireColumn(header, "count_long_short_ratio");

        List<MetricsRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] columns = line.split(",", -1);
            Instant publishedAt = LocalDateTime.parse(columns[timeColumn], CREATE_TIME)
                    .toInstant(ZoneOffset.UTC)
                    .plus(PERIOD);
            rows.add(new MetricsRow(publishedAt, decimal(columns, openInterestColumn), decimal(columns, longShortColumn)));
        }
        return rows;
    }

    private static int requireColumn(List<String> header, String name) {
        int index = header.indexOf(name);
        if (index < 0) {
            throw new IllegalStateException("Binance metrics dump has no " + name + " column: " + header);
        }
        return index;
    }

    private static BigDecimal decimal(String[] columns, int index) {
        if (index >= columns.length || columns[index].isBlank()) {
            return null;
        }
        return new BigDecimal(columns[index].trim());
    }

    /** A null value means the dump had no number for it in that row. */
    public record MetricsRow(Instant publishedAt, BigDecimal openInterest, BigDecimal longShortRatio) {}
}
