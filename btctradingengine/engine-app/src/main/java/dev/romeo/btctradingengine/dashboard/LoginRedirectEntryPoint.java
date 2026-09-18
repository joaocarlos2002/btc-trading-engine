package dev.romeo.btctradingengine.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Sends a browser that is not logged in to the Angular login page (issue #134), remembering which app page
 * it asked for in {@code returnUrl}, so a bookmarked {@code /backtest} opens again after the login. Only app
 * routes are remembered; the login page itself only follows paths inside the app.
 */
final class LoginRedirectEntryPoint implements AuthenticationEntryPoint {
    static final String LOGIN_PAGE = "/login";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.sendRedirect(request.getContextPath() + target(request));
    }

    static String target(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty() || !"GET".equals(request.getMethod()) || !SpaWebConfig.isAppRoute(relative)) {
            return LOGIN_PAGE;
        }
        String returnUrl = request.getQueryString() == null ? path : path + "?" + request.getQueryString();
        return UriComponentsBuilder.fromPath(LOGIN_PAGE)
                .queryParam("returnUrl", returnUrl)
                .encode()
                .build()
                .toUriString();
    }
}
