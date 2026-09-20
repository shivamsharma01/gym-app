package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObservabilitySecurityIT extends AbstractIntegrationTest {

    @BeforeEach
    void setUp() {
        resetDatabase();
        Tenant tenant = createTenant("Obs Gym", "obs-gym");
        createUser(tenant.getId(), "obs-admin", "obs-admin@a.local", "GYM_ADMIN");
        createUser(null, "obs-super", "obs-super@platform.local", "SUPER_ADMIN");
    }

    @Test
    void healthIsPublicWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void metricsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gymAdminCannotAccessMetrics() throws Exception {
        String token = tokenFor("obs-admin");
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanAccessMetricsAndInfo() throws Exception {
        String token = tokenFor("obs-super");
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void heapdumpIsDenied() throws Exception {
        String token = tokenFor("obs-super");
        mockMvc.perform(get("/actuator/heapdump").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void correlationIdIsPropagatedWhenSafe() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "req-test-12345"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-test-12345"))
                .andExpect(header().string("X-Correlation-Id", "req-test-12345"));
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "bad id with spaces!!!"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", Matchers.not("bad id with spaces!!!")));
    }
}
