package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end Phase 2 flow through the real HTTP stack + MySQL: plan -> member -> membership ->
 * payment -> access decision, plus freeze/unfreeze/cancel and tenant isolation.
 */
class MembershipFlowIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void setUp() {
        resetDatabase();
        Tenant tenant = createTenant("Flow Gym", "flow-gym");
        createUser(tenant.getId(), "flow-admin", "flow-admin@flow.local", "GYM_ADMIN");
        // token obtained lazily in tests via tokenFor()
    }

    @Test
    void fullLifecycle() throws Exception {
        token = tokenFor("flow-admin");

        String planId = readJson(post("/api/v1/plans",
                "{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        String memberId = readJson(post("/api/v1/members",
                "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"phone\":\"5551234\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        // No membership yet -> denied.
        getAccess(memberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("NO_MEMBERSHIP"));

        String membershipId = readJson(post("/api/v1/memberships",
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.deviceSyncState").value("NOT_SYNCED"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        // Active but unpaid -> denied for payment.
        getAccess(memberId)
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("PAYMENT_OVERDUE"));

        // Pay in full -> membership becomes PAID and access is allowed.
        post("/api/v1/payments",
                "{\"memberId\":\"" + memberId + "\",\"membershipId\":\"" + membershipId
                        + "\",\"amount\":1000.00,\"method\":\"CASH\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.receivedBy").value("flow-admin"));

        getAccess(memberId)
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("ALLOWED"))
                .andExpect(jsonPath("$.deviceSyncState").value("NOT_SYNCED"));

        // Freeze -> denied; unfreeze -> allowed again.
        post("/api/v1/memberships/" + membershipId + "/freeze", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        getAccess(memberId).andExpect(jsonPath("$.reason").value("MEMBERSHIP_FROZEN"));

        post("/api/v1/memberships/" + membershipId + "/unfreeze", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getAccess(memberId).andExpect(jsonPath("$.allowed").value(true));

        // Renew creates a new membership period (history preserved).
        post("/api/v1/memberships/" + membershipId + "/renew", "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planName").value("Monthly"));

        // Cancel -> no active membership covers today.
        post("/api/v1/memberships/" + membershipId + "/cancel",
                "{\"reason\":\"member request\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void membersAreIsolatedAcrossTenants() throws Exception {
        token = tokenFor("flow-admin");
        String memberId = readJson(post("/api/v1/members",
                "{\"firstName\":\"Private\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        // A second tenant's admin must not see the first tenant's member.
        Tenant other = createTenant("Other Gym", "other-gym");
        createUser(other.getId(), "other-admin", "other-admin@other.local", "GYM_ADMIN");
        String otherToken = tokenFor("other-admin");

        mockMvc.perform(get("/api/v1/members/" + memberId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void customDatesCanBeSetOnCreateAndUpdated() throws Exception {
        token = tokenFor("flow-admin");
        String planId = readJson(post("/api/v1/plans",
                "{\"name\":\"Custom\",\"price\":500.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String memberId = readJson(post("/api/v1/members",
                "{\"firstName\":\"Dated\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = start.plusDays(45);
        String membershipId = readJson(post("/api/v1/memberships",
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId
                        + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value(start.toString()))
                .andExpect(jsonPath("$.endDate").value(end.toString()))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        LocalDate newEnd = end.plusDays(10);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/memberships/" + membershipId + "/dates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"" + start + "\",\"endDate\":\"" + newEnd + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value(newEnd.toString()));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ResultActions post(String path, String body) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req = req.content(body);
        }
        return mockMvc.perform(req);
    }

    private ResultActions getAccess(String memberId) throws Exception {
        return mockMvc.perform(get("/api/v1/members/" + memberId + "/access")
                .header("Authorization", "Bearer " + token));
    }
}
