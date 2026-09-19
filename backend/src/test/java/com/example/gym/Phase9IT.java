package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class Phase9IT extends AbstractIntegrationTest {

    private String superToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        createUser(null, "plat-super", "plat-super@platform.local", "SUPER_ADMIN");
        superToken = tokenFor("plat-super");
    }

    @Test
    void superAdminEnrollsGymAndPublicSiteResolvesByHeader() throws Exception {
        mockMvc.perform(get("/api/v1/public/site"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"H13 Gym",
                                  "slug":"h13gym",
                                  "displayName":"H13Gym",
                                  "ownerUsername":"h13owner",
                                  "ownerEmail":"owner@h13.local",
                                  "ownerFullName":"H13 Owner",
                                  "ownerPassword":"Password123!"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.slug").value("h13gym"))
                .andExpect(jsonPath("$.tenant.displayName").value("H13Gym"))
                .andExpect(jsonPath("$.ownerUsername").value("h13owner"));

        mockMvc.perform(get("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='h13gym')]").exists());

        mockMvc.perform(get("/api/v1/public/site").header("X-Gym-Slug", "h13gym"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("h13gym"))
                .andExpect(jsonPath("$.displayName").value("H13Gym"));

        String ownerToken = tokenFor("h13owner");
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"h13staff",
                                  "email":"staff@h13.local",
                                  "fullName":"H13 Staff",
                                  "password":"Password123!",
                                  "roles":["STAFF"]
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void gymAdminCannotEnrollTenants() throws Exception {
        mockMvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Temp Gym","slug":"temp-gym","displayName":"Temp",
                                  "ownerUsername":"tempowner","ownerEmail":"temp@gym.local",
                                  "ownerFullName":"Temp Owner","ownerPassword":"Password123!"
                                }
                                """))
                .andExpect(status().isCreated());

        String ownerToken = tokenFor("tempowner");
        mockMvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Nope","slug":"nope","ownerUsername":"x","ownerEmail":"x@x.local",
                                  "ownerFullName":"X","ownerPassword":"Password123!"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanSetGymStaffPasswordWithoutOldPassword() throws Exception {
        String created = mockMvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Reset Gym","slug":"reset-gym","displayName":"Reset",
                                  "ownerUsername":"resetowner","ownerEmail":"reset@gym.local",
                                  "ownerFullName":"Reset Owner","ownerPassword":"Password123!"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tenantId = jsonMapper.readTree(created).get("tenant").get("id").asString();

        mockMvc.perform(get("/api/v1/platform/tenants/" + tenantId + "/users")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='resetowner')]").exists());

        mockMvc.perform(put("/api/v1/platform/tenants/" + tenantId + "/users/resetowner/password")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"HandedOver123!\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrEmail\":\"resetowner\",\"password\":\"Password123!\"}"))
                .andExpect(status().isUnauthorized());
        tokenForWithPassword("resetowner", "HandedOver123!");
    }

    @Test
    void superAdminCannotDisableOwnAccount() throws Exception {
        String me = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = jsonMapper.readTree(me).get("id").asString();
        mockMvc.perform(delete("/api/v1/users/" + id).header("Authorization", "Bearer " + superToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("your own account")));
    }

    private String tokenForWithPassword(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).get("accessToken").asString();
    }
}
