package dev.romeo.btctradingengine.trading;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WireMock standing in for the Binance Spot REST API (issue #103), plus checks of the signed requests. */
final class BinanceWireMock {
    static final String API_KEY = "it-api-key-vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A";
    static final String API_SECRET = "it-api-secret-NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j";

    private BinanceWireMock() {
    }

    static WireMockExtension extension() {
        return WireMockExtension.newInstance()
                .options(wireMockConfig().dynamicPort().templatingEnabled(true).globalTemplating(false))
                .build();
    }

    /** GET /api/v3/time answering the current time, as the executor syncs its clock before the first signed call. */
    static void stubServerTime(WireMockExtension wm) {
        wm.stubFor(get(urlPathEqualTo("/api/v3/time")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                // A space before the closing brace: "}}}" would read as a Handlebars triple-stash
                .withBody("{\"serverTime\":{{now format='epoch'}} }")
                .withTransformers("response-template")));
    }

    static List<LoggedRequest> requests(WireMockExtension wm, String method, String path) {
        return wm.getAllServeEvents().stream()
                .map(event -> event.getRequest())
                .filter(r -> r.getMethod().getName().equals(method) && r.getUrl().split("\\?")[0].equals(path))
                .map(r -> (LoggedRequest) r)
                .sorted((a, b) -> a.getLoggedDate().compareTo(b.getLoggedDate()))
                .toList();
    }

    static int serverTimeRequests(WireMockExtension wm) {
        return requests(wm, "GET", "/api/v3/time").size();
    }

    /** Query parameters in the order they were sent. */
    static Map<String, String> params(LoggedRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        String url = request.getUrl();
        int query = url.indexOf('?');
        if (query < 0) {
            return params;
        }
        for (String pair : url.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /**
     * What Binance checks on a SIGNED endpoint: the API key header, a timestamp, recvWindow, and signature being
     * the last parameter and the HMAC-SHA256 (hex) of the query string before it.
     */
    static void assertSigned(LoggedRequest request) {
        assertEquals(API_KEY, request.getHeader("X-MBX-APIKEY"), "X-MBX-APIKEY header");
        String url = request.getUrl();
        String query = url.substring(url.indexOf('?') + 1);
        int signatureAt = query.lastIndexOf("&signature=");
        assertTrue(signatureAt > 0, "signature must be the last parameter: " + url);
        String payload = query.substring(0, signatureAt);
        String signature = query.substring(signatureAt + "&signature=".length());
        assertEquals(hmacSha256(payload), signature, "signature of " + payload);

        Map<String, String> params = params(request);
        assertTrue(Long.parseLong(params.get("timestamp")) > 0, "timestamp");
        assertEquals("5000", params.get("recvWindow"));
        assertFalse(payload.contains("signature"));
    }

    static String hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
