package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import com.example.gym.user.AdminUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class TenantIsolationIT extends AbstractIntegrationTest {

    private AdminUser tenantBUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenantA = createTenant("Tenant A", "tenant-a");
        Tenant tenantB = createTenant("Tenant B", "tenant-b");

        createUser(tenantA.getId(), "a-admin", "a-admin@a.local", "GYM_ADMIN");
        tenantBUser = createUser(tenantB.getId(), "b-user", "b-user@b.local", "STAFF");
    }

    @Test
    void tenantAdminCannotReadUserFromAnotherTenant() throws Exception {
        String token = login("a-admin", DEFAULT_PASSWORD);
        // Fails closed: cross-tenant access is reported as NOT_FOUND, never leaking existence.
        mockMvc.perform(get("/api/v1/users/" + tenantBUser.getPublicId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void userListIsScopedToTheCallersTenant() throws Exception {
        String token = login("a-admin", DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // Only tenant A's single user is visible.
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].username").value("a-admin"));
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = jsonMapper.readTree(body);
        return node.get("accessToken").asString();
    }
}
