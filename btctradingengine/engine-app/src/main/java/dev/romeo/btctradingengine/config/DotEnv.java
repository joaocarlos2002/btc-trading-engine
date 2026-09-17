package dev.romeo.btctradingengine.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Reader of the .env next to docker-compose.yml, so the app and the database share one source of secrets. */
public final class DotEnv {
    private DotEnv() {
    }

    /**
     * Reads the first of the given .env files that exists, so `docker compose up`, `java -jar` from the reactor root and
     * `mvn spring-boot:run` (whose working directory is the engine-app module, two levels down) all find it.
     * Missing or unreadable files are simply ignored - .env is optional.
     */
    public static Map<String, String> load(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return parse(Files.readAllLines(candidate, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    System.err.println("Could not read " + candidate + ": " + e.getMessage());
                }
            }
        }
        return Map.of();
    }

    /** KEY=VALUE per line; blanks and # comments are skipped, and an optional `export ` and surrounding quotes are dropped. */
    public static Map<String, String> parse(Iterable<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int separator = line.indexOf('=');
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }
}
