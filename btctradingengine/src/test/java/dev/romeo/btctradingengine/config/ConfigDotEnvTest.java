package dev.romeo.btctradingengine.config;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The .env reader behind the acceptance criterion of issue #73: `docker compose up` and
 * `mvn spring-boot:run` must both work with nothing configured but .env.
 */
class ConfigDotEnvTest {

    @Test
    void parsesKeyValueLines() {
        Map<String, String> values = DotEnv.parse(List.of(
                "BTC_ENGINE_DB_PASSWORD=s3cret",
                "BTC_ENGINE_BINANCE_API_KEY = spaced ",
                "export BTC_ENGINE_BINANCE_API_SECRET=exported"));

        assertEquals("s3cret", values.get("BTC_ENGINE_DB_PASSWORD"));
        assertEquals("spaced", values.get("BTC_ENGINE_BINANCE_API_KEY"));
        assertEquals("exported", values.get("BTC_ENGINE_BINANCE_API_SECRET"), "an `export` prefix is dropped");
    }

    @Test
    void dropsSurroundingQuotesButKeepsInnerOnes() {
        Map<String, String> values = DotEnv.parse(List.of(
                "DOUBLE=\"pass word\"",
                "SINGLE='pass word'",
                "INNER=pa\"ss",
                "SPECIAL=p@ss=w0rd#1"));

        assertEquals("pass word", values.get("DOUBLE"));
        assertEquals("pass word", values.get("SINGLE"));
        assertEquals("pa\"ss", values.get("INNER"));
        assertEquals("p@ss=w0rd#1", values.get("SPECIAL"), "only the first = separates, and # is not a comment mid-value");
    }

    @Test
    void skipsBlanksCommentsAndGarbage() {
        Map<String, String> values = DotEnv.parse(List.of(
                "", "   ", "# a comment", "  # indented comment", "NOTAPAIR", "=novalue"));

        assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    void readsTheFirstFileThatExists(@TempDir Path dir) throws IOException {
        Path second = dir.resolve("second.env");
        Files.writeString(second, "BTC_ENGINE_DB_PASSWORD=from-second\n", StandardCharsets.UTF_8);

        Map<String, String> values = DotEnv.load(dir.resolve("missing.env"), second);

        assertEquals("from-second", values.get("BTC_ENGINE_DB_PASSWORD"));
    }

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir) {
        assertTrue(DotEnv.load(dir.resolve("nope.env")).isEmpty());
        assertTrue(DotEnv.load().isEmpty(), "no candidates at all is fine too");
    }

    @Test
    void theShippedPropertiesCarryNoSecrets() throws IOException {
        Properties shipped = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            shipped.load(in);
        }
        assertTrue(shipped.getProperty("db.password").isBlank(), "db.password must not be committed in application.properties");
        assertTrue(shipped.getProperty("binance.api.key").isBlank() && shipped.getProperty("binance.api.secret").isBlank(),
                "the Binance keys must not be committed in application.properties");
        assertFalse(shipped.getProperty("db.url").contains("polymarket"), "db.url must point at the compose database");
        assertEquals("btc_engine", shipped.getProperty("db.user"));
    }
}
