package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A super admin exports one gym's roster as CSV: members, current membership, reader ids, no biometrics. */
class PlatformRosterExportIT extends AbstractIntegrationTest {

    private String token;
    private String tenantPublicId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Export Gym", "export-gym");
        tenantPublicId = tenant.getPublicId();
        createUser(tenant.getId(), "exp-admin", "exp-admin@export.local", "GYM_ADMIN");
        token = tokenFor("exp-admin");
    }

    @Test
    void platformExportCsvIncludesRosterWithoutBiometrics() throws Exception {
        createMemberWithFace(token, "{\"firstName\":\"Export\",\"lastName\":\"Me\",\"serialNumber\":\"3001\"}")
                .andExpect(status().isCreated());

        createUser(null, "plat-sa", "plat-sa@platform.local", "SUPER_ADMIN");
        String saToken = tokenFor("plat-sa");

        String csv = mockMvc.perform(get("/api/v1/platform/tenants/" + tenantPublicId + "/members/export.csv")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("memberCode,firstName,lastName,status,email,phone,planName,startDate,endDate,deviceUserIds\n");
        assertThat(csv).contains("Export");
        assertThat(csv).doesNotContain("face");
        assertThat(csv).doesNotContain("template");
    }
}
