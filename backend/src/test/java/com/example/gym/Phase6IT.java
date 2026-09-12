package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class Phase6IT extends AbstractIntegrationTest {

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Downtown Fitness", "downtown-fitness");
        createUser(tenant.getId(), "p6-admin", "p6-admin@demo.local", "GYM_ADMIN");
        adminToken = tokenFor("p6-admin");
    }

    @Test
    void publicEnquiryAndStaffList() throws Exception {
        mockMvc.perform(post("/api/v1/public/enquiries")
                        .header("X-Gym-Slug", "downtown-fitness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ada","email":"ada@example.com","phone":"999","message":"Trial?","planInterest":"Monthly"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(get("/api/v1/enquiries").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("ada@example.com"));
    }

    @Test
    void reportsRequirePermissionAndReturnAggregates() throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/summary").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membersTotal").value(0));
    }

    @Test
    void settingsAndMockNotificationTemplate() throws Exception {
        mockMvc.perform(put("/api/v1/settings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"True Gym Downtown","tagline":"Train here","about":"A gym.","phone":"111","email":"hi@gym.local","address":"1 High St","hours":"6-22"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("True Gym Downtown"));

        mockMvc.perform(get("/api/v1/public/site").header("X-Gym-Slug", "downtown-fitness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("True Gym Downtown"));

        mockMvc.perform(put("/api/v1/notification-templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateKey":"EXPIRY_REMINDER","channel":"EMAIL","subject":"Hi {{memberName}}","body":"Expires {{expiryDate}}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateKey").value("EXPIRY_REMINDER"));
    }

    @Test
    void publicPlansDoNotRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/public/plans").header("X-Gym-Slug", "downtown-fitness"))
                .andExpect(status().isOk());
        assertThat(true).isTrue();
    }
}
