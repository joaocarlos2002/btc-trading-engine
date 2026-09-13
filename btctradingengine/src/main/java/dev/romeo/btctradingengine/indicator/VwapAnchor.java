package dev.romeo.btctradingengine.indicator;

/** How the VWAP accumulator is anchored: reset every UTC day, or a sliding window of candles. */
public enum VwapAnchor {
    DAILY,
    ROLLING;

    /** Falls back to DAILY for unknown/blank values so a typo in the properties file is not fatal. */
    public static VwapAnchor fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return DAILY;
        }
        return ROLLING.name().equalsIgnoreCase(value.trim()) ? ROLLING : DAILY;
    }
}
