package dev.romeo.btctradingengine.derivatives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BinanceMetricsArchiveTest {

    private static final String HEADER = "create_time,symbol,sum_open_interest,sum_open_interest_value,"
            + "count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,"
            + "sum_taker_long_short_vol_ratio";

    @Test
    public void rowsAreKeyedByPublicationTimeNotCreateTime() {
        // Real row from 2026-09-10: the REST API published 105043.987 and 1.2758 with timestamp 00:05
        List<BinanceMetricsArchive.MetricsRow> rows = BinanceMetricsArchive.parseCsv(HEADER + "\n"
                + "2026-09-10 00:00:00,BTCUSDT,105043.9870000000000000,8226729929.879,1.33195904,2.19360200,1.27565999,2.45746400\n");

        BinanceMetricsArchive.MetricsRow row = rows.get(0);
        assertEquals(Instant.parse("2026-09-10T00:05:00Z"), row.publishedAt());
        assertEquals(0, row.openInterest().compareTo(new BigDecimal("105043.987")));
        assertEquals(0, row.longShortRatio().compareTo(new BigDecimal("1.27565999")));
    }

    @Test
    public void readsColumnsByNameAndToleratesEmptyValuesAndUnsortedRows() {
        String reordered = "symbol,count_long_short_ratio,create_time,sum_open_interest\n"
                + "BTCUSDT,1.6,2026-09-10 23:35:00,\n"
                + "BTCUSDT,1.5,2026-09-10 22:55:00,107335.463\n";

        List<BinanceMetricsArchive.MetricsRow> rows = BinanceMetricsArchive.parseCsv(reordered);

        assertEquals(Instant.parse("2026-09-10T23:40:00Z"), rows.get(0).publishedAt());
        assertNull(rows.get(0).openInterest());
        assertEquals(0, rows.get(0).longShortRatio().compareTo(new BigDecimal("1.6")));
        assertEquals(0, rows.get(1).openInterest().compareTo(new BigDecimal("107335.463")));
    }

    @Test
    public void missingRequiredColumnFailsLoudly() {
        assertThrows(IllegalStateException.class,
                () -> BinanceMetricsArchive.parseCsv("create_time,symbol\n2026-09-10 00:00:00,BTCUSDT\n"));
    }

    @Test
    public void loadsACachedDayWithoutTouchingTheNetwork(@TempDir Path cacheDir) throws Exception {
        String csv = HEADER + "\n2026-09-10 00:00:00,BTCUSDT,105043.987,1,1,1,1.27565999,1\n";
        Files.write(cacheDir.resolve("BTCUSDT-metrics-2026-09-10.zip"), zip("BTCUSDT-metrics-2026-09-10.csv", csv));
        // Unroutable base URL: a network call would fail the test
        BinanceMetricsArchive archive = new BinanceMetricsArchive("http://127.0.0.1:9", cacheDir);

        List<BinanceMetricsArchive.MetricsRow> rows =
                archive.load("BTCUSDT", LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-10"));

        assertEquals(1, rows.size());
        assertEquals(Instant.parse("2026-09-10T00:05:00Z"), rows.get(0).publishedAt());
    }

    private static byte[] zip(String entryName, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
