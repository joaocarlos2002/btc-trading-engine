package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.Main;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With no password configured (the shipped default) nobody can log in - the app fails closed. */
@SpringBootTest(classes = Main.class, properties = {"dashboard.auth.password=", "engine.pipeline.enabled=false"})
@AutoConfigureMockMvc
class DashboardSecurityBlankPasswordTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void noCredentialsAreAccepted() throws Exception {
        mvc.perform(get("/api/stats").with(httpBasic("trader", "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats").with(httpBasic("trader", "trader")))
                .andExpect(status().isUnauthorized());
    }
}
