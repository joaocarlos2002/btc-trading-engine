package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.Main;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criteria of issue #98: no endpoint answers without authentication, and a POST without
 * the CSRF token is refused.
 *
 * <p>The CSRF token is taken the way the dashboard's JS takes it - from the XSRF-TOKEN cookie of a
 * previous response, echoed back in the X-XSRF-TOKEN header - instead of using the {@code csrf()}
 * post-processor, which does not know about the SPA token handler configured here.
 */
@SpringBootTest(classes = Main.class, properties = {
        "engine.pipeline.enabled=false",
        "dashboard.auth.username=trader",
        "dashboard.auth.password=s3cret",
        "dashboard.auth.roles=VIEWER,TRADER"
})
@AutoConfigureMockMvc
class DashboardSecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    /** The CSRF cookie a logged-in browser would be holding after loading the page. */
    private Cookie csrfCookie(String... roles) throws Exception {
        Cookie csrf = mvc.perform(get("/api/stats").with(user("someone").roles(roles)))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf, "every response must carry the XSRF-TOKEN cookie the dashboard reads");
        return csrf;
    }

    /** A POST already carrying that cookie and echoing it back in the header, as the dashboard does. */
    private MockHttpServletRequestBuilder postWithCsrf(String path, String... roles) throws Exception {
        Cookie csrf = csrfCookie(roles);
        return post(path).with(user("someone").roles(roles))
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
    }

    @Test
    void anonymousApiCallsGet401() throws Exception {
        mvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/backtest?days=1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trades/open")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousPagesAndTheWebSocketAreSentToTheLoginForm() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/ws/live")).andExpect(status().is3xxRedirection());
    }

    @Test
    void aDeepLinkReturnsToItsPageAfterTheLogin() throws Exception {
        mvc.perform(get("/backtest")).andExpect(redirectedUrl("/login?returnUrl=/backtest"));
        mvc.perform(get("/live").queryParam("tab", "flow"))
                .andExpect(redirectedUrl("/login?returnUrl=/live?tab%3Dflow"));
        // Only app pages are remembered
        mvc.perform(get("/")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/ws/live")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void theLoginPageAndItsStaticFilesAreReachable() throws Exception {
        // /login is the Angular app (a stub index.html under src/test/resources/static)
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("spa-test-index")));
        mvc.perform(get("/main-test.js")).andExpect(status().isOk());
    }

    @Test
    void theLoginFormAnswersWithAStatus() throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf, "the 401 must still hand out the XSRF-TOKEN cookie the login form posts with");

        mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .param("username", "trader").param("password", "s3cret"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .param("username", "trader").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        // Without the token the login itself is refused
        mvc.perform(post("/api/auth/login").param("username", "trader").param("password", "s3cret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSessionEndpointReportsTheRoles() throws Exception {
        mvc.perform(get("/api/auth/me").with(user("trader").roles("VIEWER", "TRADER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("trader"))
                .andExpect(jsonPath("$.roles[0]").value("TRADER"))
                .andExpect(jsonPath("$.roles[1]").value("VIEWER"));
    }

    @Test
    void logoutNeedsTheCsrfTokenAndAnswers204() throws Exception {
        mvc.perform(post("/api/auth/logout").with(user("trader").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(postWithCsrf("/api/auth/logout", "VIEWER")).andExpect(status().isNoContent());
    }

    @Test
    void theConfiguredCredentialsAuthenticate() throws Exception {
        mvc.perform(get("/api/stats").with(httpBasic("trader", "s3cret"))).andExpect(status().isOk());
        // Wrong credentials must come back as 401, not as a redirect to the login form.
        mvc.perform(get("/api/stats").with(httpBasic("trader", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats").with(httpBasic("someoneelse", "s3cret"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aPostWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/api/trades/manual/buy").with(user("trader").roles("TRADER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/trades/manual/close").with(user("trader").roles("TRADER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPostWithTheWrongCsrfTokenIsRefused() throws Exception {
        Cookie csrf = csrfCookie("TRADER");
        mvc.perform(post("/api/trades/manual/buy").with(user("someone").roles("TRADER"))
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", "not-the-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTraderWithTheCsrfTokenReachesTheHandler() throws Exception {
        // 400 is the controller answering "position manager is not ready": security let it through.
        mvc.perform(postWithCsrf("/api/trades/manual/buy", "VIEWER", "TRADER")).andExpect(status().isBadRequest());
        mvc.perform(postWithCsrf("/api/trades/manual/close", "VIEWER", "TRADER")).andExpect(status().isBadRequest());
    }

    @Test
    void backtestJobEndpointsNeedLoginAndTheCsrfToken() throws Exception {
        mvc.perform(get("/api/backtests/some-id")).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/backtests", "/api/backtests/sweep", "/api/backtests/walk-forward"}) {
            mvc.perform(post(path).with(user("someone").roles("VIEWER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void backtestRequestsAreValidatedBeforeAnyDownload() throws Exception {
        // 400 with the error list, answered before any Binance call (issue #83)
        mvc.perform(get("/api/backtest?days=1&smaPeriod=0&confirmationSnapshots=0").with(user("someone").roles("VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("indicator.sma.period must be positive")))
                .andExpect(jsonPath("$.errors", hasItem("prediction.confirmation.snapshots must be positive")));
        mvc.perform(get("/api/backtest?days=1&smaPeriod=abc").with(user("someone").roles("VIEWER")))
                .andExpect(status().isBadRequest());

        mvc.perform(postWithCsrf("/api/backtests", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 0, \"params\": {\"macdFastPeriod\": 30, \"macdSlowPeriod\": 26}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("days must be between 1 and 180")))
                .andExpect(jsonPath("$.errors", hasItem("indicator.macd.fast.period must be lower than slow.period")));
        mvc.perform(postWithCsrf("/api/backtests/sweep", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 5, \"param\": \"buyThreshold\", \"values\": [0.3, 5]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(postWithCsrf("/api/backtests/walk-forward", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\": 10, \"param\": \"buyThreshold\", \"values\": [0.3], "
                                + "\"trainDays\": 10, \"testDays\": 5, \"objective\": \"winRate\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/backtests/unknown").with(user("someone").roles("VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aViewerCanWatchButNotPlaceOrders() throws Exception {
        mvc.perform(get("/api/stats").with(user("watcher").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(postWithCsrf("/api/trades/manual/buy", "VIEWER")).andExpect(status().isForbidden());
    }
}
