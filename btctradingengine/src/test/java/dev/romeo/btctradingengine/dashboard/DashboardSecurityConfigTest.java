package dev.romeo.btctradingengine.dashboard;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
@SpringBootTest(classes = DashboardApplication.class, properties = {
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
        mvc.perform(get("/backtest.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/ws/live")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theLoginPageItselfIsReachable() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
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
    void aViewerCanWatchButNotPlaceOrders() throws Exception {
        mvc.perform(get("/api/stats").with(user("watcher").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(postWithCsrf("/api/trades/manual/buy", "VIEWER")).andExpect(status().isForbidden());
    }
}
