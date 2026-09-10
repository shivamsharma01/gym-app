package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class AuthAndSecurityIT extends AbstractIntegrationTest {

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tenantRepository.deleteAll();
        tenant = createTenant("Acme Gym", "acme-gym");
        createUser(tenant.getId(), "acme-admin", "admin@acme.local", "GYM_ADMIN");
        createUser(tenant.getId(), "acme-frontdesk", "fd@acme.local", "FRONT_DESK");
    }

    @Test
    void protectedEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void adminWithUserManageCanListUsers() throws Exception {
        String token = login("acme-admin", DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void frontDeskWithoutUserManageIsForbidden() throws Exception {
        String token = login("acme-frontdesk", DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void meReturnsRolesAndPermissions() throws Exception {
        String token = login("acme-admin", DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("acme-admin"))
                .andExpect(jsonPath("$.roles[0]").value("GYM_ADMIN"))
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void refreshRotatesTokenAndOldTokenIsRejected() throws Exception {
        JsonNode login = loginJson("acme-admin", DEFAULT_PASSWORD);
        String firstRefresh = login.get("refreshToken").asString();

        // First refresh succeeds and returns a new refresh token.
        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("refreshToken", firstRefresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondRefresh = jsonMapper.readTree(body).get("refreshToken").asString();
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // Reusing the now-rotated first token is rejected.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("refreshToken", firstRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedBadPasswordsLockTheAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("acme-frontdesk", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        // Even the correct password is now rejected because the account is locked.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme-frontdesk", DEFAULT_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("locked")));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String login(String username, String password) throws Exception {
        return loginJson(username, password).get("accessToken").asString();
    }

    private JsonNode loginJson(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body);
    }

    private String loginBody(String username, String password) {
        return "{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String json(String key, String value) {
        return "{\"" + key + "\":\"" + value + "\"}";
    }
}
