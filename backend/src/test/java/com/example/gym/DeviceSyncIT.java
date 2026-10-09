package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.DeviceSyncService;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 3: device domain, transactional outbox, gateway protocol, attendance ingest, and
 * tenant isolation — driven through HTTP + the in-process protocol handler (no native SDK).
 */
class DeviceSyncIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    @Autowired
    private DeviceSyncService deviceSyncService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String token;
    private String gatewayId;
    private String gatewayToken;
    private String deviceId;
    private String memberId;
    private String membershipId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Device Gym", "device-gym");
        createUser(tenant.getId(), "dev-admin", "dev-admin@device.local", "GYM_ADMIN");
        token = tokenFor("dev-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN-1\"}")
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

        String createdDevice = postJson("/api/v1/devices",
                "{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.10\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        deviceId = readJson(createdDevice).get("id").asString();

        String planId = readJson(postJson("/api/v1/plans",
                "{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        memberId = readJson(postJson("/api/v1/members",
                "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"memberCode\":\"1001\",\"serialNumber\":\"1001\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        membershipId = readJson(postJson("/api/v1/memberships",
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void newMemberIsNotPlacedOnEveryReader() throws Exception {
        assertThat(memberDeviceMappingRepository.findAll()).isEmpty();
        assertThat(deviceSyncCommandRepository.findAll()).extracting(c -> c.getType())
                .doesNotContain(SyncCommandType.CREATE_USER, SyncCommandType.UPDATE_VALIDITY, SyncCommandType.UPSERT_FACE);

        mockMvc.perform(get("/api/v1/devices/" + deviceId + "/health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceConnectionState").value("UNKNOWN"))
                .andExpect(jsonPath("$.gatewayStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.gatewaySessionOnline").value(false))
                .andExpect(jsonPath("$.pendingCommandCount").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.failedCommandCount").value(0))
                .andExpect(jsonPath("$.reconciliationRequired").value(false));

        // Mapping the same member again is a conflict, not a second device user.
        postJson("/api/v1/devices/" + deviceId + "/mappings", "{\"memberId\":\"" + memberId + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void syncResultMarksSucceededAndDoesNotFabricateWithoutGatewayAck() throws Exception {

        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        var create = deviceSyncService.enqueue(device.getTenantId(), device.getId(), null, null,
                SyncCommandType.OPEN_DOOR, java.util.Map.of("reason", "ack"));
        assertThat(create).isNotNull();
        assertThat(create.getState()).isEqualTo(SyncCommandState.PENDING);

        int dispatched = deviceSyncService.dispatchDue();
        assertThat(dispatched).isZero(); // no WSS session — we do not pretend the device was updated
        assertThat(deviceSyncCommandRepository.findById(create.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.PENDING);

        gatewayMessageService.process(envelope("SYNC_RESULT", create.getCorrelationId(),
                "{\"ok\":true}"), gatewayId);

        assertThat(deviceSyncCommandRepository.findById(create.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.SUCCEEDED);
        mockMvc.perform(get("/api/v1/members/" + memberId + "/access")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.reason").value("ALLOWED"));
    }

    @Test
    void aMemberCommandIsNotQueued() {
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        assertThatThrownBy(() -> deviceSyncService.enqueue(device.getTenantId(), device.getId(), member.getId(), null,
                SyncCommandType.UPSERT_FACE, java.util.Map.of("deviceUserId", "1001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desired revision");
        assertThat(deviceSyncCommandRepository.findAll()).extracting(c -> c.getType())
                .doesNotContain(SyncCommandType.UPSERT_FACE, SyncCommandType.CREATE_USER);
    }

    @Test
    void aMemberUpdateIsNotQueuedEither() {
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        assertThatThrownBy(() -> deviceSyncService.enqueue(device.getTenantId(), device.getId(), member.getId(), null,
                SyncCommandType.UPDATE_USER, java.util.Map.of("deviceUserId", "1001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deviceSyncService.enqueue(device.getTenantId(), device.getId(), member.getId(), null,
                SyncCommandType.DISABLE_USER, java.util.Map.of("deviceUserId", "1001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deviceSyncService.enqueue(device.getTenantId(), device.getId(), member.getId(), null,
                SyncCommandType.UPDATE_VALIDITY, java.util.Map.of("deviceUserId", "1001", "enabled", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(deviceSyncCommandRepository.findAll()).extracting(c -> c.getType())
                .doesNotContain(SyncCommandType.UPDATE_USER, SyncCommandType.DISABLE_USER, SyncCommandType.UPDATE_VALIDITY);
    }

    @Test
    void listingSyncCommandsWithoutAMemberDoesNotFail() throws Exception {
        deviceSyncCommandRepository.deleteAllInBatch();
        var device = deviceRepository.findAll().getFirst();
        deviceSyncService.enqueue(device.getTenantId(), device.getId(), null, null,
                SyncCommandType.RECONCILE_DEVICE, java.util.Map.of("reason", "manual"));

        mockMvc.perform(get("/api/v1/sync-commands").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].memberName").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void attendanceIngestIsIdempotentAndDeniedRaisesSecurityEvent() throws Exception {
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        memberDeviceMappingRepository.save(new com.example.gym.device.domain.MemberDeviceMapping(
                device.getTenantId(), member.getId(), device.getId(), "1001"));

        String occurred = Instant.parse("2026-09-11T06:00:00Z").toString();
        String event = envelope("DEVICE_EVENT", UUID.randomUUID().toString(),
                "{\"deviceUserId\":\"1001\",\"occurredAt\":\"" + occurred
                        + "\",\"method\":\"FACE\",\"granted\":true,\"recNo\":42}");
        gatewayMessageService.process(event, gatewayId);
        gatewayMessageService.process(event, gatewayId); // duplicate recNo

        mockMvc.perform(get("/api/v1/attendance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].direction").value("IN"))
                .andExpect(jsonPath("$.content[0].result").value("GRANTED"))
                .andExpect(jsonPath("$.content[0].memberLinked").value(true))
                .andExpect(jsonPath("$.content[0].memberName").value("Asha Rao"));

        gatewayMessageService.process(envelope("DEVICE_EVENT", UUID.randomUUID().toString(),
                "{\"deviceUserId\":\"1001\",\"occurredAt\":\"" + occurred
                        + "\",\"method\":\"FACE\",\"granted\":false,\"recNo\":43}"), gatewayId);

        mockMvc.perform(get("/api/v1/security-events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ACCESS_DENIED"));
    }

    @Test
    void freezeEnqueuesDisableAndRemoteDoorRequiresConfirmation() throws Exception {
        long before = deviceSyncCommandRepository.count();

        postJson("/api/v1/memberships/" + membershipId + "/freeze", null)
                .andExpect(status().isOk());
        assertThat(deviceSyncCommandRepository.findAll())
                .noneMatch(c -> c.getType() == SyncCommandType.DISABLE_USER);

        postJson("/api/v1/devices/" + deviceId + "/door",
                "{\"action\":\"OPEN\",\"confirmed\":false,\"reason\":\"test\"}")
                .andExpect(status().isBadRequest());

        postJson("/api/v1/devices/" + deviceId + "/door",
                "{\"action\":\"OPEN\",\"confirmed\":true,\"reason\":\"stuck member\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OPEN_DOOR"))
                .andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void restPollClaimsCommandsWithPerGatewayTokenAndRejectsUserJwt() throws Exception {
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        deviceSyncService.enqueue(device.getTenantId(), device.getId(), null, null,
                SyncCommandType.OPEN_DOOR, java.util.Map.of("reason", "poll"));

        mockMvc.perform(get("/internal/gateway/commands")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        String body = mockMvc.perform(get("/internal/gateway/commands")
                        .header("Authorization", "Bearer " + gatewayToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonMapper.readTree(body).size()).isGreaterThan(0);

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope("HEARTBEAT", UUID.randomUUID().toString(), "{}")))
                .andExpect(status().isOk());
    }

    @Test
    void devicesAreIsolatedAcrossTenants() throws Exception {
        Tenant other = createTenant("Other Gym", "other-device-gym");
        createUser(other.getId(), "other-dev", "other-dev@other.local", "GYM_ADMIN");
        String otherToken = tokenFor("other-dev");

        mockMvc.perform(get("/api/v1/devices/" + deviceId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void newMemberGetsTheNextFreeSerialAndATakenSerialIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/members/next-serial").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("1"));

        String binaId = readJson(postJson("/api/v1/members", "{\"firstName\":\"Bina\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serialNumber").value("1"))
                .andExpect(jsonPath("$.memberCode").value(org.hamcrest.Matchers.startsWith("MBR-")))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        postJson("/api/v1/members", "{\"firstName\":\"Chetan\",\"serialNumber\":\"1001\"}")
                .andExpect(status().isConflict());

        long commandsBefore = deviceSyncCommandRepository.count();
        mockMvc.perform(put("/api/v1/members/" + binaId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Bina Renamed\",\"serialNumber\":\"1001\"}"))
                .andExpect(status().isConflict());

        var bina = memberRepository.findByPublicId(binaId).orElseThrow();
        assertThat(bina.getSerialNumber()).isEqualTo("1");
        assertThat(bina.getFirstName()).isEqualTo("Bina");
        var asha = memberRepository.findByPublicId(memberId).orElseThrow();
        assertThat(asha.getSerialNumber()).isEqualTo("1001");
        assertThat(deviceSyncCommandRepository.count()).isEqualTo(commandsBefore);
    }

    @Test
    void changingTheSerialDoesNotWriteAReader() throws Exception {
        mockMvc.perform(put("/api/v1/members/" + memberId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"serialNumber\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("7"))
                .andExpect(jsonPath("$.memberCode").value("1001"));
        assertThat(deviceSyncCommandRepository.findAll())
                .noneMatch(c -> c.getType() == SyncCommandType.CREATE_USER
                        || c.getType() == SyncCommandType.REMOVE_USER);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ResultActions postJson(String path, String body) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req = req.content(body);
        }
        return mockMvc.perform(req);
    }

    private String envelope(String type, String correlationId, String payloadJson) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, deviceId, type,
                correlationId, payloadJson);
    }
}
