package dev.romeo.btctradingengine.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criteria of issue #66: a request without the token is refused with 401, and a blank
 * configured token authorizes nobody.
 */
class ApiTokenInterceptorTest {

    /** Captures what preHandle wrote, so the real interceptor is exercised without a servlet container. */
    private static final class ResponseSpy {
        int status = 200;
        String contentType;
        final StringWriter body = new StringWriter();
    }

    private static HttpServletRequest request(String sentToken) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                ApiTokenInterceptorTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> "X-Api-Token".equalsIgnoreCase((String) args[0]) ? sentToken : null;
                    case "getRequestURI" -> "/api/trades/manual/buy";
                    default -> null;
                });
    }

    private static HttpServletResponse response(ResponseSpy spy) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                ApiTokenInterceptorTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> spy.status = (int) args[0];
                        case "setContentType" -> spy.contentType = (String) args[0];
                        case "getWriter" -> {
                            return new PrintWriter(spy.body);
                        }
                        default -> { }
                    }
                    return null;
                });
    }

    private static ResponseSpy preHandle(String configuredToken, String sentToken) throws Exception {
        ResponseSpy spy = new ResponseSpy();
        boolean proceed = new ApiTokenInterceptor(configuredToken)
                .preHandle(request(sentToken), response(spy), new Object());
        assertEquals(proceed, spy.status == 200, "an authorized request must not touch the response");
        return spy;
    }

    @Test
    void acceptsTheConfiguredToken() {
        assertTrue(new ApiTokenInterceptor("s3cret").isAuthorized("s3cret"));
    }

    @Test
    void rejectsAWrongOrMissingToken() {
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor("s3cret");
        assertFalse(interceptor.isAuthorized("s3cre"), "a prefix of the token is not the token");
        assertFalse(interceptor.isAuthorized("s3crets"), "a longer string is not the token");
        assertFalse(interceptor.isAuthorized(""));
        assertFalse(interceptor.isAuthorized(null), "no header at all");
    }

    @Test
    void aBlankConfiguredTokenAuthorizesNobody() {
        for (String configured : new String[]{null, "", "   "}) {
            ApiTokenInterceptor interceptor = new ApiTokenInterceptor(configured);
            assertFalse(interceptor.isAuthorized(""), "blank config must fail closed");
            assertFalse(interceptor.isAuthorized("anything"), "blank config must fail closed");
        }
    }

    @Test
    void postWithoutTokenGets401() throws Exception {
        ResponseSpy spy = preHandle("s3cret", null);
        assertEquals(401, spy.status);
        assertTrue(spy.contentType.startsWith("application/json"));
        assertTrue(spy.body.toString().contains("X-Api-Token"), spy.body.toString());
    }

    @Test
    void postWithTheTokenIsLetThrough() throws Exception {
        assertEquals(200, preHandle("s3cret", "s3cret").status);
    }
}
