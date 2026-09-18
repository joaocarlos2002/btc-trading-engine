package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.Main;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #134: a refresh on an Angular route gets index.html, without swallowing /api/**, /ws/** or missing
 * files. The index.html here is the stub under src/test/resources/static.
 */
@SpringBootTest(classes = Main.class, properties = {
        "engine.pipeline.enabled=false",
        "dashboard.auth.password=s3cret"
})
@AutoConfigureMockMvc
class SpaWebConfigTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void appRoutesFallBackToIndexHtml() throws Exception {
        // "/" is Boot's welcome page: a forward to index.html, which MockMvc reports instead of following
        mvc.perform(get("/").with(user("trader").roles("VIEWER"))).andExpect(forwardedUrl("index.html"));
        for (String route : new String[]{"/backtest", "/live/anything/deeper"}) {
            mvc.perform(get(route).with(user("trader").roles("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("spa-test-index")));
        }
        mvc.perform(get("/backtest").with(user("trader").roles("VIEWER")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
    }

    @Test
    void apiAndMissingFilesAreStill404() throws Exception {
        mvc.perform(get("/api/does-not-exist").with(user("trader").roles("VIEWER"))).andExpect(status().isNotFound());
        mvc.perform(get("/missing-chunk.js").with(user("trader").roles("VIEWER"))).andExpect(status().isNotFound());
        mvc.perform(get("/media/missing.woff2").with(user("trader").roles("VIEWER"))).andExpect(status().isNotFound());
    }

    @Test
    void hashedBundlesAndFontsArePublicAndCachedForAYear() throws Exception {
        // Anonymous on purpose: the login page needs them before anyone is logged in
        for (String path : new String[]{"/main-test.js", "/media/test-font.woff2"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
        }
    }

    @Test
    void onlyExtensionlessPathsOutsideTheServerPrefixesAreAppRoutes() {
        assertTrue(SpaWebConfig.isAppRoute("backtest"));
        assertTrue(SpaWebConfig.isAppRoute("live/positions"));
        assertFalse(SpaWebConfig.isAppRoute("api/stats"));
        assertFalse(SpaWebConfig.isAppRoute("ws/live"));
        assertFalse(SpaWebConfig.isAppRoute("actuator/health"));
        assertFalse(SpaWebConfig.isAppRoute("chunk-abc.js"));
    }
}
