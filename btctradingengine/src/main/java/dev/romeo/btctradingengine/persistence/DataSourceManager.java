package dev.romeo.btctradingengine.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.romeo.btctradingengine.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

public class DataSourceManager {
    private static final Logger logger = LoggerFactory.getLogger(DataSourceManager.class);
    private static HikariDataSource dataSource;

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("PostgreSQL driver not found", e);
            throw new RuntimeException(e);
        }
    }

    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(Config.getDbUrl());
            config.setUsername(Config.getDbUser());
            config.setPassword(Config.getDbPassword());
            config.setMaximumPoolSize(Config.getDbPoolSize());
            config.setIdleTimeout(Config.getDbIdleTimeoutMs());
            config.setMaxLifetime(Config.getDbMaxLifetimeMs());
            config.setPoolName("btc-trading-engineBTCPool");

            dataSource = new HikariDataSource(config);
            logger.info("HikariCP connection pool initialized with {} connections", Config.getDbPoolSize());
        }
        return dataSource;
    }

    public static synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            logger.info("Connection pool closed");
        }
    }
}

