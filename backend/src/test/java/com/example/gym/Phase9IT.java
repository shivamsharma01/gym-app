package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
