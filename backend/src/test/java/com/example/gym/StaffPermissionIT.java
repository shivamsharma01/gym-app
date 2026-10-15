package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** What the seeded STAFF role may and may not do, next to a gym admin. */
class StaffPermissionIT extends AbstractIntegrationTest {

    private String admin;
    private String staff;
    private String planId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Perm Gym", "perm-gym");
        createUser(tenant.getId(), "perm-admin", "perm-admin@perm.local", "GYM_ADMIN");
        createUser(tenant.getId(), "perm-staff", "perm-staff@perm.local", "STAFF");
        admin = tokenFor("perm-admin");
        staff = tokenFor("perm-staff");
        planId = readJson(send(post("/api/v1/plans"), admin,
                "{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void staffReadTheGymProfileButCannotChangeIt() throws Exception {
        mockMvc.perform(get("/api/v1/settings").header("Authorization", "Bearer " + staff))
                .andExpect(status().isOk());
        send(put("/api/v1/settings"), staff, "{\"name\":\"Taken over\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAGymAdminMakesAMemberATerminalAdmin() throws Exception {
        createMemberWithFace(staff, "{\"firstName\":\"Ravi\",\"deviceAuthority\":\"ADMIN\"}")
                .andExpect(status().isForbidden());
        String plain = createMemberId(staff, "{\"firstName\":\"Asha\",\"deviceAuthority\":\"USER\"}");
        String owner = createMemberId(admin, "{\"firstName\":\"Owner\",\"deviceAuthority\":\"ADMIN\"}");

        send(put("/api/v1/members/" + plain), staff, "{\"firstName\":\"Asha\",\"deviceAuthority\":\"ADMIN\"}")
                .andExpect(status().isForbidden());
        send(put("/api/v1/members/" + owner), staff, "{\"firstName\":\"Owner\",\"deviceAuthority\":\"USER\"}")
                .andExpect(status().isForbidden());
        send(put("/api/v1/members/" + owner), staff, "{\"firstName\":\"Owner\",\"lastName\":\"Shah\",\"deviceAuthority\":\"ADMIN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceAuthority").value("ADMIN"));
        send(put("/api/v1/members/" + plain), staff, "{\"firstName\":\"Asha\",\"lastName\":\"Rao\"}")
                .andExpect(status().isOk());
    }

    @Test
    void staffEditADiscountedMembershipWithoutTouchingTheDiscount() throws Exception {
        String memberId = createMemberId(admin, "{\"firstName\":\"Neha\"}");
        LocalDate start = LocalDate.now();
        send(post("/api/v1/memberships"), staff,
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\",\"discountAmount\":100}")
                .andExpect(status().isForbidden());
        String membershipId = readJson(send(post("/api/v1/memberships"), admin,
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\",\"discountAmount\":100}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        send(put("/api/v1/memberships/" + membershipId), staff, update(start, start.plusDays(40), "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value(start.plusDays(40).toString()))
                .andExpect(jsonPath("$.discountAmount").value(100));
        send(put("/api/v1/memberships/" + membershipId), staff, update(start, start.plusDays(40), "200"))
                .andExpect(status().isForbidden());
        send(put("/api/v1/memberships/" + membershipId), staff, update(start, start.plusDays(40), "1500"))
                .andExpect(status().isBadRequest());
    }

    private String update(LocalDate start, LocalDate end, String discount) {
        return "{\"planId\":\"" + planId + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\",\"discountAmount\":" + discount + "}";
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String body) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
