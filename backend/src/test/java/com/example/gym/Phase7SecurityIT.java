package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Phase 7 security-focused API checks that complement AuthAndSecurityIT / Phase9IT.
 */
class Phase7SecurityIT extends AbstractIntegrationTest {

    private Tenant tenantA;
    private Tenant tenantB;

    @BeforeEach
    void setUp() {
        resetDatabase();
        tenantA = createTenant("Alpha Gym", "alpha-gym");
        tenantB = createTenant("Beta Gym", "beta-gym");
        createUser(tenantA.getId(), "alpha-admin", "alpha@a.local", "GYM_ADMIN");
        createUser(tenantB.getId(), "beta-admin", "beta@b.local", "GYM_ADMIN");
        createUser(null, "sec-super", "sec-super@platform.local", "SUPER_ADMIN");
    }

    @Test
    void publicSiteRequiresGymSlugWhenNoFallbackConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/public/site"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicSiteIsIsolatedByGymSlugHeader() throws Exception {
        mockMvc.perform(get("/api/v1/public/site").header("X-Gym-Slug", "alpha-gym"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("alpha-gym"));

        mockMvc.perform(get("/api/v1/public/site").header("X-Gym-Slug", "beta-gym"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("beta-gym"));
    }

    @Test
    void gymAdminCannotReadOtherTenantsMembers() throws Exception {
        String alphaToken = tokenFor("alpha-admin");
        // Create a member in beta via beta admin.
        String betaToken = tokenFor("beta-admin");
        String created = mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + betaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Beta","lastName":"Member","phone":"9000000001"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String memberId = jsonMapper.readTree(created).get("id").asString();

        mockMvc.perform(get("/api/v1/members/" + memberId).header("Authorization", "Bearer " + alphaToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void securityHeadersDenyFraming() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void superAdminCanListPlatformTenants() throws Exception {
        String token = tokenFor("sec-super");
        mockMvc.perform(get("/api/v1/platform/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='alpha-gym')]").exists())
                .andExpect(jsonPath("$[?(@.slug=='beta-gym')]").exists());
    }
}
