package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Device domain, transactional outbox, gateway protocol, attendance ingest, and
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

        memberId = createMemberId(token, "{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"memberCode\":\"1001\",\"serialNumber\":\"1001\"}");

        membershipId = readJson(postJson("/api/v1/memberships",
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void aNewMemberReachesTheReaderByADesiredRevisionNotACommand() throws Exception {
        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId())).isPresent();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(device.getId(), member.getId())).isPresent();
        assertThat(deviceSyncCommandRepository.findAll()).isEmpty();

        mockMvc.perform(get("/api/v1/devices/" + deviceId + "/health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceConnectionState").value("UNKNOWN"))
                .andExpect(jsonPath("$.gatewayStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.gatewaySessionOnline").value(false))
                .andExpect(jsonPath("$.pendingCommandCount").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.failedCommandCount").value(0))
                .andExpect(jsonPath("$.lastPunchAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void syncResultMarksSucceededAndDoesNotFabricateWithoutGatewayAck() throws Exception {

        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        var create = deviceSyncService.enqueue(device.getTenantId(), device.getId(),
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
    void staffCommandsAreListed() throws Exception {
        deviceSyncCommandRepository.deleteAllInBatch();
        var device = deviceRepository.findAll().getFirst();
        deviceSyncService.enqueue(device.getTenantId(), device.getId(),
                SyncCommandType.OPEN_DOOR, java.util.Map.of("reason", "manual"));

        mockMvc.perform(get("/api/v1/sync-commands").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("OPEN_DOOR"));
    }

    @Test
    void attendanceIngestIsIdempotentAndDeniedRaisesSecurityEvent() throws Exception {
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        String deviceUserId = memberDeviceMappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElseThrow().getDeviceUserId();

        String occurred = Instant.parse("2026-09-11T06:00:00Z").toString();
        String window = "{\"deviceId\":\"" + deviceId + "\",\"punches\":[{\"deviceUserId\":\"" + deviceUserId
                + "\",\"occurredAt\":\"" + occurred + "\",\"method\":\"FACE\",\"granted\":true,\"recNo\":42}]}";
        postWindow(window);
        postWindow(window); // the same window read again

        mockMvc.perform(get("/api/v1/attendance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].direction").value("IN"))
                .andExpect(jsonPath("$.content[0].result").value("GRANTED"))
                .andExpect(jsonPath("$.content[0].memberLinked").value(true))
                .andExpect(jsonPath("$.content[0].memberName").value("Asha Rao"));

        postWindow("{\"deviceId\":\"" + deviceId + "\",\"punches\":[{\"deviceUserId\":\"" + deviceUserId
                + "\",\"occurredAt\":\"" + occurred + "\",\"method\":\"FACE\",\"granted\":false,\"recNo\":43}]}");

        mockMvc.perform(get("/api/v1/security-events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ACCESS_DENIED"));

        String quiet = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Exit\",\"role\":\"EXIT\",\"host\":\"10.0.0.12\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(get("/api/v1/security-events").param("deviceId", deviceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/security-events").param("deviceId", quiet)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void freezeQueuesNoDeviceCommandAndARemoteDoorNeedsConfirmation() throws Exception {
        long before = deviceSyncCommandRepository.count();

        postJson("/api/v1/memberships/" + membershipId + "/freeze", null)
                .andExpect(status().isOk());
        assertThat(deviceSyncCommandRepository.findAll()).isEmpty();

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
        deviceSyncService.enqueue(device.getTenantId(), device.getId(),
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
    void staffCanAskOneReaderToBeReadNowAndARepeatedAskReusesTheOpenCommand() throws Exception {
        long before = deviceSyncCommandRepository.count();
        String first = postJson("/api/v1/devices/" + deviceId + "/scan", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SCAN_READER"))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String again = postJson("/api/v1/devices/" + deviceId + "/scan", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(readJson(again).get("id").asString()).isEqualTo(readJson(first).get("id").asString());
        assertThat(deviceSyncCommandRepository.count()).isEqualTo(before + 1);
        assertThat(auditLogRepository.findAll())
                .filteredOn(a -> "DEVICE_SCAN_REQUESTED".equals(a.getAction()))
                .hasSize(1);

        String claimed = mockMvc.perform(get("/internal/gateway/commands")
                        .header("Authorization", "Bearer " + gatewayToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(claimed).contains("SCAN_READER");
        var scan = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> c.getType() == SyncCommandType.SCAN_READER).findFirst().orElseThrow();
        gatewayMessageService.process(envelope("SYNC_RESULT", scan.getCorrelationId(), "{\"ok\":true}"), gatewayId);
        assertThat(deviceSyncCommandRepository.findById(scan.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.SUCCEEDED);

        postJson("/api/v1/devices/" + deviceId + "/scan", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"));
        assertThat(deviceSyncCommandRepository.count()).isEqualTo(before + 2);
    }

    @Test
    void readingAReaderNowNeedsDeviceSyncAGatewayAndTheSameGym() throws Exception {
        Long tenantId = deviceRepository.findByPublicId(deviceId).orElseThrow().getTenantId();
        createUser(tenantId, "dev-staff", "dev-staff@device.local", "STAFF");
        mockMvc.perform(post("/api/v1/devices/" + deviceId + "/scan")
                        .header("Authorization", "Bearer " + tokenFor("dev-staff")))
                .andExpect(status().isForbidden());

        String loose = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Loose\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.11\",\"port\":37777}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        postJson("/api/v1/devices/" + loose + "/scan", null).andExpect(status().isBadRequest());

        Tenant other = createTenant("Other Gym", "other-scan-gym");
        createUser(other.getId(), "other-scan", "other-scan@other.local", "GYM_ADMIN");
        mockMvc.perform(post("/api/v1/devices/" + deviceId + "/scan")
                        .header("Authorization", "Bearer " + tokenFor("other-scan")))
                .andExpect(status().isNotFound());

        assertThat(deviceSyncCommandRepository.findAll())
                .noneMatch(c -> c.getType() == SyncCommandType.SCAN_READER);
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
    void newMemberGetsSuggestedSerialAndDuplicateSerialIsAllowed() throws Exception {
        // Asha holds device user id 1; next-serial skips it as a suggestion only.
        mockMvc.perform(get("/api/v1/members/next-serial").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("2"));

        String binaId = readJson(createMemberWithFace(token, "{\"firstName\":\"Bina\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serialNumber").value("2"))
                .andExpect(jsonPath("$.memberCode").value(org.hamcrest.Matchers.startsWith("MBR-")))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        createMemberWithFace(token, "{\"firstName\":\"Chetan\",\"serialNumber\":\"1001\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serialNumber").value("1001"));

        long commandsBefore = deviceSyncCommandRepository.count();
        mockMvc.perform(put("/api/v1/members/" + binaId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Bina Renamed\",\"serialNumber\":\"1001\"}"))
                .andExpect(status().isOk());

        var bina = memberRepository.findByPublicId(binaId).orElseThrow();
        assertThat(bina.getSerialNumber()).isEqualTo("1001");
        assertThat(bina.getFirstName()).isEqualTo("Bina Renamed");
        var asha = memberRepository.findByPublicId(memberId).orElseThrow();
        assertThat(asha.getSerialNumber()).isEqualTo("1001");
        // Serial edit alone does not enqueue device sync commands.
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
        assertThat(deviceSyncCommandRepository.findAll()).isEmpty();
    }


    private ResultActions postJson(String path, String body) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req = req.content(body);
        }
        return mockMvc.perform(req);
    }

    private void postWindow(String window) throws Exception {
        mockMvc.perform(post("/internal/gateway/attendance").header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON).content(window))
                .andExpect(status().isOk());
    }

    private String envelope(String type, String correlationId, String payloadJson) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, deviceId, type,
                correlationId, payloadJson);
    }
}
