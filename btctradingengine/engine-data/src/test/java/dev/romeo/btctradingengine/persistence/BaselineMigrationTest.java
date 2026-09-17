package dev.romeo.btctradingengine.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V1__baseline.sql replaces the DDL the engine ran at startup before Flyway (issue #102): db-schema.sql,
 * TradeJournal's trades/execution_log DDL and JdbcOrderCommandStore's order_commands DDL. The expected
 * names below were taken from those sources, so nothing they created is lost in the baseline.
 */
class BaselineMigrationTest {

    private static final Set<String> OLD_TABLES = Set.of(
            "candles", "ticks", "derivatives_snapshots", "order_book_snapshots",
            "trades", "execution_log", "order_commands");

    private static final Set<String> OLD_INDEXES = Set.of(
            "idx_candles_symbol_time", "idx_candles_close_time", "uq_candles_symbol_open_time",
            "idx_ticks_symbol_time", "idx_ticks_time",
            "idx_derivatives_symbol_time", "idx_order_book_symbol_time",
            "idx_trades_symbol", "idx_trades_entry_time", "idx_trades_status",
            "idx_exec_position", "idx_exec_time",
            "idx_order_commands_unresolved", "idx_order_commands_position");

    /** Columns the old code added with ALTER TABLE ... ADD COLUMN IF NOT EXISTS. */
    private static final Map<String, Set<String>> OLD_ADDED_COLUMNS = Map.of(
            "candles", Set.of("taker_buy_volume", "large_buy_volume", "large_sell_volume", "flow_source"),
            "ticks", Set.of("aggressor_side"),
            "trades", Set.of("quantity", "state"));

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_INDEX =
            Pattern.compile("CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    @Test
    void baselineCreatesEveryTableTheOldSchemaHad() throws IOException {
        Map<String, String> tables = tables(baseline());
        assertEquals(OLD_TABLES, tables.keySet());
    }

    @Test
    void baselineCreatesEveryIndexTheOldSchemaHad() throws IOException {
        Set<String> indexes = new LinkedHashSet<>();
        Matcher m = CREATE_INDEX.matcher(baseline());
        while (m.find()) {
            indexes.add(m.group(1));
        }
        assertEquals(OLD_INDEXES, indexes);
    }

    @Test
    void baselineIncludesTheColumnsTheOldCodeAddedLater() throws IOException {
        Map<String, String> tables = tables(baseline());
        OLD_ADDED_COLUMNS.forEach((table, columns) -> columns.forEach(column ->
                assertTrue(Pattern.compile("^\\s*" + column + "\\s", Pattern.MULTILINE).matcher(tables.get(table)).find(),
                        table + "." + column + " missing from V1")));
    }

    private static Map<String, String> tables(String sql) {
        Map<String, String> tables = new java.util.LinkedHashMap<>();
        Matcher m = CREATE_TABLE.matcher(sql);
        while (m.find()) {
            tables.put(m.group(1), m.group(2));
        }
        return tables;
    }

    private static String baseline() throws IOException {
        try (InputStream in = BaselineMigrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__baseline.sql")) {
            assertNotNull(in, "db/migration/V1__baseline.sql not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
