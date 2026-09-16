package dev.romeo.btctradingengine.config;

import java.nio.file.Path;

final class CacheDirs {
    private CacheDirs() {
    }

    /** A blank setting means {@code <java.io.tmpdir>/btc-trading-engine/<name>}. */
    static Path resolve(String configured, String name) {
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "btc-trading-engine", name)
                : Path.of(configured);
    }
}
