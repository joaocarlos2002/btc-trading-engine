package dev.romeo.btctradingengine.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The real migrations on real PostgreSQL (issue #103): every table and index exists, and a second
 * run is a no-op.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {

    @BeforeAll
    static void database() {
        PostgresTestDatabase.dataSource();
    }

    @Test
    void migrationsCreateEveryTable() throws SQLException {
        for (String table :
                List.of(
                        "candles",
                        "ticks",
                        "derivatives_snapshots",
                        "order_book_snapshots",
                        "trades",
                        "execution_log",
                        "order_commands")) {
            assertEquals(
                    1,
                    PostgresTestDatabase.count(
                            "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                            table),
                    table);
        }
    }

    @Test
    void conflictTargetsAndPartialIndexExist() throws SQLException {
        // ON CONFLICT (symbol, open_time_ms) in DatabaseWriter needs this unique index to exist
        assertEquals(
                1,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_candles_symbol_open_time' AND indexdef LIKE 'CREATE UNIQUE%'"));
        assertEquals(
                1,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_order_commands_unresolved' AND indexdef LIKE '%WHERE%'"));
    }

    @Test
    void secondMigrateRunAppliesNothing() {
        MigrateResult again =
                Flyway.configure()
                        .dataSource(PostgresTestDatabase.dataSource())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
        assertEquals(0, again.migrationsExecuted);
        assertTrue(again.success);
    }

    @Test
    void legacyDatabaseIsBaselinedWithoutRunningV1() throws SQLException {
        // What LivePipelineConfiguration does for a pre-Flyway database: tables exist, no history
        // table
        var ds = PostgresTestDatabase.dataSource();
        try (var connection = ds.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS legacy CASCADE");
            statement.execute("CREATE SCHEMA legacy");
            statement.execute("CREATE TABLE legacy.ticks (id BIGSERIAL PRIMARY KEY)");
        }
        MigrateResult result =
                Flyway.configure()
                        .dataSource(ds)
                        .schemas("legacy")
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(true)
                        .baselineVersion("1")
                        .load()
                        .migrate();
        assertEquals(0, result.migrationsExecuted);
        assertEquals(
                0,
                PostgresTestDatabase.count(
                        "SELECT count(*) FROM pg_tables WHERE schemaname = 'legacy' AND tablename = 'candles'"));
        try (var connection = ds.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA legacy CASCADE");
        }
    }
}
