package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.EnrollmentStatus;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.member.MemberCreationSource;
import com.example.gym.membership.MembershipPaymentStatus;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class DeviceRosterImportIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    private String token;
    private String gatewayId;
    private String gatewayToken;
    private String entranceId;
    private String exitId;
    private String tenantPublicId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Import Gym", "import-gym");
        tenantPublicId = tenant.getPublicId();
        createUser(tenant.getId(), "imp-admin", "imp-admin@import.local", "GYM_ADMIN");
        token = tokenFor("imp-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN-imp\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayId = readJson(createdGateway).get("id").asString();
        String enrollmentToken = readJson(createdGateway).get("token").asString();
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        gatewayToken = readJson(enrolled).get("credential").asString();

        entranceId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.10\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        exitId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Exit\",\"role\":\"EXIT\",\"host\":\"10.0.0.11\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void importCreatesMembersUnknownPaidMappingsAndIsIdempotent() throws Exception {
        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[
                  {"deviceUserId":"2001","name":"Ada Lovelace","frozen":false,
                   "validFrom":"2024-01-01T00:00:00.000Z","validTo":"2026-12-31T00:00:00.000Z"},
                  {"deviceUserId":"2002","name":"Frozen User","frozen":true},
                  {"deviceUserId":"2003","name":"NoEnd","frozen":false,"validFrom":"2025-01-01T00:00:00.000Z"}
                ]}
                """));
        gatewayMessageService.process(envelope(exitId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[
                  {"deviceUserId":"2001","name":"Ada Lovelace","frozen":false,
                   "validFrom":"2024-01-01T00:00:00.000Z","validTo":"2026-12-31T00:00:00.000Z"}
                ]}
                """));

        long commandsBefore = deviceSyncCommandRepository.count();

        String first = postJson("/api/v1/devices/" + entranceId + "/import-users", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.inactiveFrozen").value(1))
                .andExpect(jsonPath("$.inferredEndDates").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(first).get("mapped").asInt()).isGreaterThanOrEqualTo(4);

        // Import must not enqueue CREATE_USER for roster rows (faces stay on device)
        long createUsers = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> c.getType() == SyncCommandType.CREATE_USER)
                .count();
        assertThat(createUsers).isZero();
        assertThat(deviceSyncCommandRepository.count()).isGreaterThanOrEqualTo(commandsBefore);

        mockMvc.perform(get("/api/v1/members?q=Ada").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creationSource").value("DEVICE_IMPORT"))
                .andExpect(jsonPath("$.content[0].memberCode").value("2001"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/members?q=Frozen").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("INACTIVE"));

        String adaId = readJson(mockMvc.perform(get("/api/v1/members?q=2001")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("content").get(0).get("id").asString();

        String memberships = mockMvc.perform(get("/api/v1/members/" + adaId + "/memberships")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(memberships).get(0).get("planName").asString()).isEqualTo("Unknown");
        assertThat(readJson(memberships).get(0).get("paymentStatus").asString())
                .isEqualTo(MembershipPaymentStatus.PAID.name());
        assertThat(readJson(memberships).get(0).get("endDateInferred").asBoolean()).isFalse();

        String noEndId = readJson(mockMvc.perform(get("/api/v1/members?q=2003")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("content").get(0).get("id").asString();
        String noEndMs = mockMvc.perform(get("/api/v1/members/" + noEndId + "/memberships")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(noEndMs).get(0).get("endDateInferred").asBoolean()).isTrue();

        // Dual-device: Ada mapped on both
        assertThat(memberDeviceMappingRepository.findAll().stream()
                .filter(m -> "2001".equals(m.getDeviceUserId())).count()).isEqualTo(2);
        assertThat(memberDeviceMappingRepository.findAll().stream()
                .filter(m -> "2001".equals(m.getDeviceUserId()))
                .allMatch(m -> m.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)).isTrue();

        // Idempotent re-import
        postJson("/api/v1/devices/" + entranceId + "/import-users", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0));

        assertThat(memberRepository.count()).isEqualTo(3);

        // Manual create still MANUAL
        String manual = postJson("/api/v1/members", "{\"firstName\":\"Manual\",\"lastName\":\"User\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(manual).get("creationSource").asString())
                .isEqualTo(MemberCreationSource.MANUAL.name());
    }

    @Test
    void platformExportCsvIncludesRosterWithoutBiometrics() throws Exception {
        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[
                  {"deviceUserId":"3001","name":"Export Me","frozen":false,
                   "validFrom":"2025-01-01T00:00:00.000Z","validTo":"2026-01-01T00:00:00.000Z"}
                ]}
                """));
        postJson("/api/v1/devices/" + entranceId + "/import-users", null)
                .andExpect(status().isOk());

        createUser(null, "plat-sa", "plat-sa@platform.local", "SUPER_ADMIN");
        String saToken = tokenFor("plat-sa");

        String csv = mockMvc.perform(get("/api/v1/platform/tenants/" + tenantPublicId + "/members/export.csv")
                        .header("Authorization", "Bearer " + saToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("memberCode,firstName");
        assertThat(csv).contains("3001");
        assertThat(csv).contains("DEVICE_IMPORT");
        assertThat(csv).doesNotContain("face");
        assertThat(csv).doesNotContain("template");
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req = req.content(body);
        }
        return mockMvc.perform(req);
    }

    private String envelope(String devicePublicId, String type, String payloadJson) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId, type,
                UUID.randomUUID(), payloadJson);
    }
}
