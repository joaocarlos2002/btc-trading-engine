package dev.romeo.btctradingengine.config;

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
        Map<String, String> values = Config.parseDotEnv(List.of(
                "BTC_ENGINE_DB_PASSWORD=s3cret",
                "BTC_ENGINE_BINANCE_API_KEY = spaced ",
                "export BTC_ENGINE_BINANCE_API_SECRET=exported"));

        assertEquals("s3cret", values.get("BTC_ENGINE_DB_PASSWORD"));
        assertEquals("spaced", values.get("BTC_ENGINE_BINANCE_API_KEY"));
        assertEquals("exported", values.get("BTC_ENGINE_BINANCE_API_SECRET"), "an `export` prefix is dropped");
    }

    @Test
    void dropsSurroundingQuotesButKeepsInnerOnes() {
        Map<String, String> values = Config.parseDotEnv(List.of(
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
        Map<String, String> values = Config.parseDotEnv(List.of(
                "", "   ", "# a comment", "  # indented comment", "NOTAPAIR", "=novalue"));

        assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    void readsTheFirstFileThatExists(@TempDir Path dir) throws IOException {
        Path second = dir.resolve("second.env");
        Files.writeString(second, "BTC_ENGINE_DB_PASSWORD=from-second\n", StandardCharsets.UTF_8);

        Map<String, String> values = Config.loadDotEnv(dir.resolve("missing.env"), second);

        assertEquals("from-second", values.get("BTC_ENGINE_DB_PASSWORD"));
    }

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir) {
        assertTrue(Config.loadDotEnv(dir.resolve("nope.env")).isEmpty());
        assertTrue(Config.loadDotEnv().isEmpty(), "no candidates at all is fine too");
    }

    @Test
    void theShippedPropertiesCarryNoSecrets() {
        assertTrue(Config.getDbPassword().isBlank() || System.getenv("BTC_ENGINE_DB_PASSWORD") != null,
                "db.password must not be committed in application.properties");
        assertFalse(Config.getDbUrl().contains("polymarket"), "db.url must point at the compose database");
        assertEquals("btc_engine", Config.getDbUser());
    }
}
