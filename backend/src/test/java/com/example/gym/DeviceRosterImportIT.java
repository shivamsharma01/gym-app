package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DeviceMemberImporter;
import com.example.gym.device.DeviceService;
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

    @Autowired
    private DeviceService deviceService;

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
    void reconcileAutoImportsDeviceUsersWithoutWritingBack() throws Exception {
        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[
                  {"deviceUserId":"2001","name":"Ada Lovelace","frozen":false,
                   "validFrom":"2024-01-01T00:00:00.000Z","validTo":"2026-12-31T00:00:00.000Z"},
                  {"deviceUserId":"2002","name":"Frozen User","frozen":true},
                  {"deviceUserId":"2003","name":"NoEnd","frozen":false,"validFrom":"2025-01-01T00:00:00.000Z"}
                ]}
                """), gatewayId);
        gatewayMessageService.process(envelope(exitId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[
                  {"deviceUserId":"2001","name":"Ada Lovelace","frozen":false,
                   "validFrom":"2024-01-01T00:00:00.000Z","validTo":"2026-12-31T00:00:00.000Z"}
                ]}
                """), gatewayId);

        assertThat(memberRepository.count()).isZero();
        assertThat(memberDeviceMappingRepository.findAll()).isEmpty();
        assertThat(reconciliationConflictRepository.findAll()).isEmpty();
        assertThat(deviceSyncCommandRepository.findAll()).noneMatch(c -> c.getType() == SyncCommandType.CREATE_USER
                || c.getType() == SyncCommandType.DISABLE_USER
                || c.getType() == SyncCommandType.UPDATE_VALIDITY
                || c.getType() == SyncCommandType.REPORT_DEVICE_USER);

        postJson("/api/v1/devices/" + entranceId + "/import-users", null)
                .andExpect(status().isBadRequest());
        assertThat(memberRepository.count()).isZero();

        String manual = postJson("/api/v1/members", "{\"firstName\":\"Manual\",\"lastName\":\"User\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(readJson(manual).get("creationSource").asString())
                .isEqualTo(MemberCreationSource.MANUAL.name());
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    void platformExportCsvIncludesRosterWithoutBiometrics() throws Exception {
        postJson("/api/v1/members", "{\"firstName\":\"Export\",\"lastName\":\"Me\",\"serialNumber\":\"3001\"}")
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

    @Test
    void anIncompleteReaderListDoesNotFlagMappedUsersAsMissing() throws Exception {
        String memberId = readJson(postJson("/api/v1/members",
                "{\"firstName\":\"Om\",\"lastName\":\"Das\",\"memberCode\":\"4001\",\"serialNumber\":\"4001\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        Long entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        memberDeviceMappingRepository.save(new com.example.gym.device.domain.MemberDeviceMapping(
                member.getTenantId(), member.getId(), entrance, "4001"));

        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"usersComplete":false,"deviceUsers":[]}
                """), gatewayId);
        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"deviceUsers":[]}
                """), gatewayId);

        assertThat(reconciliationConflictRepository.findAll())
                .noneMatch(c -> c.getConflictType().name().equals("MISSING_ON_DEVICE"));
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(entrance, "4001")).isPresent();
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void anUnchangedReaderSkipsTheComparisonUntilTheServerQueuesAUserChange() throws Exception {
        Long entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        Long tenantId = deviceRepository.findById(entrance).orElseThrow().getTenantId();
        var member = memberRepository.save(new com.example.gym.member.Member(tenantId, "4001", "Om"));
        memberDeviceMappingRepository.save(new com.example.gym.device.domain.MemberDeviceMapping(
                tenantId, member.getId(), entrance, "4001"));

        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT",
                """
                {"ok":true,"rosterDigest":"v1:empty","deviceUsers":[]}
                """), gatewayId);
        assertThat(reconciliationConflictRepository.findAll()).isEmpty();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(entrance, "4001")).isPresent();
        assertThat(deviceService.reconcile(entranceId, tenantId).getType()).isEqualTo(SyncCommandType.RECONCILE_DEVICE);
        assertThat(deviceService.syncNow(entranceId, tenantId).getType()).isEqualTo(SyncCommandType.RECONCILE_DEVICE);
    }

    @Test
    void aStaleComparisonIsNotTrusted() throws Exception {
        Long entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        Long tenantId = deviceRepository.findById(entrance).orElseThrow().getTenantId();
        deviceRepository.saveRosterDigest(entrance, "v1:old", Instant.now().minus(java.time.Duration.ofHours(25)));

        assertThat(readJson(deviceService.reconcile(entranceId, tenantId).getPayload()).has("knownDigest")).isFalse();
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
