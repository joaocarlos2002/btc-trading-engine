package dev.romeo.btctradingengine.persistence;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One PostgreSQL container for every integration test of the module run (issue #103), started on first use
 * and migrated with the real Flyway migrations of {@code db/migration}, never a hand-written schema.
 * Tests call {@link #truncateAll()} to start from empty tables instead of paying for a new container.
 *
 * <p>Only reached from {@code *IT} classes annotated {@code @Testcontainers(disabledWithoutDocker = true)}, so
 * without Docker they are skipped before this class starts anything.
 */
// Copy of engine-data's test helper: test classes are not shared between modules
public final class PostgresTestDatabase {
    /** Same major version as docker-compose.yml. */
    static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;

    private PostgresTestDatabase() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            PostgreSQLContainer<?> started = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("btc_engine_it")
                    .withUsername("it")
                    .withPassword("it");
            started.start();
            // Also stops it when Ryuk is disabled (TESTCONTAINERS_RYUK_DISABLED=true)
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "postgres-it-stop"));

            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(started.getJdbcUrl());
            ds.setUser(started.getUsername());
            ds.setPassword(started.getPassword());
            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            dataSource = ds;
        }
        return dataSource;
    }

    /** Empties every application table (not Flyway's history) and resets the id sequences. */
    public static void truncateAll() throws SQLException {
        try (Connection connection = dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            StringBuilder tables = new StringBuilder();
            try (ResultSet rs = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                    + "AND tablename <> 'flyway_schema_history'")) {
                while (rs.next()) {
                    tables.append(tables.isEmpty() ? "" : ", ").append(rs.getString(1));
                }
            }
            if (!tables.isEmpty()) {
                statement.execute("TRUNCATE " + tables + " RESTART IDENTITY");
            }
        }
    }

    /** Single-value query helper for assertions. */
    public static Object queryValue(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource().getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    public static long count(String sql, Object... parameters) throws SQLException {
        Object value = queryValue(sql, parameters);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
