package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.auth.AuthRefreshCookie;
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
        resetDatabase();
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
    void gymAdminCanListRolesForUserAssignment() throws Exception {
        String token = login("acme-admin", DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='STAFF')]").isNotEmpty());
    }

    @Test
    void gymAdminCanListPermissions() throws Exception {
        String token = login("acme-admin", DEFAULT_PASSWORD);
        mockMvc.perform(get("/api/v1/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='MEMBER_VIEW')]").isNotEmpty());
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
    void userCanChangeOwnPasswordWithCurrentPassword() throws Exception {
        String token = login("acme-admin", DEFAULT_PASSWORD);
        mockMvc.perform(post("/api/v1/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-old","newPassword":"NewPassword123!"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Password123!","newPassword":"NewPassword123!"}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme-admin", DEFAULT_PASSWORD)))
                .andExpect(status().isUnauthorized());
        login("acme-admin", "NewPassword123!");
    }

    @Test
    void adminCannotDisableOwnAccount() throws Exception {
        String token = login("acme-admin", DEFAULT_PASSWORD);
        String me = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = jsonMapper.readTree(me).get("id").asString();
        mockMvc.perform(delete("/api/v1/users/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("your own account")));
    }

    @Test
    void refreshRotatesTokenAndOldTokenIsRejected() throws Exception {
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme-admin", DEFAULT_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        jakarta.servlet.http.Cookie firstCookie = loginResult.getResponse().getCookie(AuthRefreshCookie.COOKIE_NAME);
        assertThat(firstCookie).isNotNull();
        String firstRefresh = firstCookie.getValue();

        // Cookie-based refresh succeeds and rotates the cookie.
        var refreshResult = mockMvc.perform(post("/api/v1/auth/refresh").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();
        jakarta.servlet.http.Cookie secondCookie = refreshResult.getResponse().getCookie(AuthRefreshCookie.COOKIE_NAME);
        assertThat(secondCookie).isNotNull();
        assertThat(secondCookie.getValue()).isNotEqualTo(firstRefresh);

        // Body refresh still works for the rotated token value (transition / IT helpers).
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("refreshToken", firstRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsRefreshCookie() throws Exception {
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("acme-admin", DEFAULT_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        jakarta.servlet.http.Cookie refresh = loginResult.getResponse().getCookie(AuthRefreshCookie.COOKIE_NAME);
        assertThat(refresh).isNotNull();

        var logoutResult = mockMvc.perform(post("/api/v1/auth/logout").cookie(refresh))
                .andExpect(status().isNoContent())
                .andReturn();
        jakarta.servlet.http.Cookie cleared = logoutResult.getResponse().getCookie(AuthRefreshCookie.COOKIE_NAME);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
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
