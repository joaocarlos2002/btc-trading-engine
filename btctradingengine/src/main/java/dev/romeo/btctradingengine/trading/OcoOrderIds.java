package dev.romeo.btctradingengine.trading;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Deterministic clientOrderIds of a position's OCO (issue #99): derived from the position id, so a
 * restart finds the protection on Binance without persisting anything.
 */
final class OcoOrderIds {
    static final int MAX_CLIENT_ORDER_ID_LENGTH = 36;
    private static final String LIST_SUFFIX = "-oco";
    private static final String TARGET_SUFFIX = "-tp";
    private static final String STOP_SUFFIX = "-sl";

    private OcoOrderIds() {
    }

    static String list(String symbol, String positionId) {
        return base(symbol, positionId) + LIST_SUFFIX;
    }

    static String target(String symbol, String positionId) {
        return base(symbol, positionId) + TARGET_SUFFIX;
    }

    static String stop(String symbol, String positionId) {
        return base(symbol, positionId) + STOP_SUFFIX;
    }

    static boolean isLeg(String clientOrderId) {
        return clientOrderId != null && clientOrderId.startsWith("btce-")
                && (clientOrderId.endsWith(TARGET_SUFFIX) || clientOrderId.endsWith(STOP_SUFFIX));
    }

    /** The list id a leg belongs to: both share the base. */
    static Optional<String> listOfLeg(String clientOrderId) {
        if (!isLeg(clientOrderId)) {
            return Optional.empty();
        }
        return Optional.of(clientOrderId.substring(0, clientOrderId.length() - TARGET_SUFFIX.length()) + LIST_SUFFIX);
    }

    /**
     * "btce-SYMBOL-POSITION" while the longest id still fits Binance's 36 characters; beyond that
     * (e.g. BINANCE_&lt;orderId&gt; positions) a hash of symbol and position keeps it short and stable.
     */
    private static String base(String symbol, String positionId) {
        String base = "btce-" + symbol + "-" + positionId;
        if (base.length() + LIST_SUFFIX.length() <= MAX_CLIENT_ORDER_ID_LENGTH) {
            return base;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((symbol + "-" + positionId).getBytes(StandardCharsets.UTF_8));
            return "btce-" + HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
