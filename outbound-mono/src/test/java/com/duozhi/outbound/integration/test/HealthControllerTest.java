package com.duozhi.outbound.integration.test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.duozhi.outbound.health.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {
    @Test
    void livenessHasNoDependencyOnDatabaseCacheOrBroker() throws Exception {
        MockMvcBuilders.standaloneSetup(new HealthController())
                .build()
                .perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }
}
