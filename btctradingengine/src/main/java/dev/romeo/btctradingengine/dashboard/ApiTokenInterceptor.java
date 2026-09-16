package dev.romeo.btctradingengine.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires the {@code X-Api-Token} header on the endpoints that change state or cost real work
 * (manual orders, backtest). A custom header is not a CORS simple request, so a POST from another
 * site needs a preflight that this app never allows - which is what stops the CSRF described in
 * issue #66. Same-origin calls from the dashboard itself send no preflight at all.
 *
 * <p>Fails closed: with {@code dashboard.api.token} blank nothing is authorized, so a default
 * install cannot be driven remotely before a token is configured.
 */
public class ApiTokenInterceptor implements HandlerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(ApiTokenInterceptor.class);
    static final String HEADER = "X-Api-Token";

    private final String token;
    private boolean blankTokenWarned;

    public ApiTokenInterceptor(String token) {
        this.token = token == null ? "" : token;
    }

    /**
     * Constant-time comparison, so the response time does not leak how much of the token matched.
     * A blank configured token never authorizes anything.
     */
    boolean isAuthorized(String sentToken) {
        if (token.isBlank() || sentToken == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), sentToken.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (isAuthorized(request.getHeader(HEADER))) {
            return true;
        }
        if (token.isBlank() && !blankTokenWarned) {
            blankTokenWarned = true;
            logger.warn("dashboard.api.token is not set: {} is refused for everyone. "
                    + "Set it in application.properties (or BTC_TRADING_ENGINE_DASHBOARD_API_TOKEN) to use manual "
                    + "orders and the backtest endpoint.", request.getRequestURI());
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Missing or invalid " + HEADER + " header\"}");
        return false;
    }
}
