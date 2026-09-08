package dev.romeo.btctradingengine.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

public class DatabaseInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);

    public static void initializeSchema() {
        try {
            String schema = loadSchema();
            if (schema == null || schema.trim().isEmpty()) {
                logger.error("Failed to load database schema");
                return;
            }

            try (Connection conn = DataSourceManager.getDataSource().getConnection();
                 Statement stmt = conn.createStatement()) {

                String[] statements = schema.split(";");
                for (String sql : statements) {
                    String trimmed = sql.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed + ";");
                    }
                }

                logger.info("Database schema initialized successfully");
            }
        } catch (Exception e) {
            logger.error("Error initializing database schema", e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    private static String loadSchema() {
        try (InputStream is = DatabaseInitializer.class.getClassLoader()
                .getResourceAsStream("db-schema.sql")) {

            if (is == null) {
                logger.error("db-schema.sql not found in resources");
                return null;
            }

            return new BufferedReader(new InputStreamReader(is))
                    .lines()
                    .filter(line -> !line.trim().isEmpty() && !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            logger.error("Error loading database schema", e);
            return null;
        }
    }
}

