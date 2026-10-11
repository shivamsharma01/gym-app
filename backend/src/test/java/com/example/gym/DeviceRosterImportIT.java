package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.member.MemberCreationSource;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class DeviceRosterImportIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    private String token;
    private String gatewayId;
    private String entranceId;
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
        mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk());

        entranceId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.10\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void anOldReconcileResultIsAcknowledgedAndWritesNothing() throws Exception {
        Long entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        Long tenantId = deviceRepository.findById(entrance).orElseThrow().getTenantId();
        var member = saveMember(tenantId, "4001", "Om", "Das", "4001");
        memberDeviceMappingRepository.save(new com.example.gym.device.domain.MemberDeviceMapping(
                tenantId, member.getId(), entrance, "4001"));

        String reply = gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[
                  {"deviceUserId":"2001","name":"Ada Lovelace","frozen":false}
                ],
                 "events":[{"deviceUserId":"2001","occurredAt":"2026-10-02T06:00:00Z",
                            "method":"FACE","granted":true,"recNo":501}]}
                """), gatewayId).orElseThrow();

        assertThat(reply).contains("ACK");
        assertThat(attendanceEventRepository.findAll()).isEmpty();
        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(memberDeviceMappingRepository.findAll()).singleElement()
                .satisfies(m -> assertThat(m.getDeviceUserId()).isEqualTo("4001"));
        assertThat(reconciliationConflictRepository.findAll()).isEmpty();
        assertThat(deviceSyncCommandRepository.findAll()).noneMatch(c -> c.getType() == SyncCommandType.CREATE_USER
                || c.getType() == SyncCommandType.DISABLE_USER
                || c.getType() == SyncCommandType.UPDATE_VALIDITY
                || c.getType() == SyncCommandType.REPORT_DEVICE_USER);
    }

    @Test
    void readerUsersAreNotImportedAndTheAttendanceRequestEndpointsAreGone() throws Exception {
        postJson("/api/v1/devices/" + entranceId + "/import-users", null)
                .andExpect(status().isBadRequest());
        assertThat(memberRepository.count()).isZero();
        for (String removed : new String[] {"/reconcile", "/sync-now"}) {
            int status = postJson("/api/v1/devices/" + entranceId + removed, null).andReturn().getResponse().getStatus();
            assertThat(HttpStatus.valueOf(status).is2xxSuccessful()).isFalse();
        }
        assertThat(deviceSyncCommandRepository.findAll()).isEmpty();

        String manual = createMemberWithFace(token, "{\"firstName\":\"Manual\",\"lastName\":\"User\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(manual).get("creationSource").asString())
                .isEqualTo(MemberCreationSource.MANUAL.name());
        assertThat(memberRepository.count()).isEqualTo(1);
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
        assertThat(csv).contains("memberCode,firstName");
        assertThat(csv).contains("Export");
        assertThat(csv).contains("MANUAL");
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
