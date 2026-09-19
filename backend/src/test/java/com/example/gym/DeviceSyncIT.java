package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                "{\"firstName\":\"Asha\",\"lastName\":\"Rao\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        membershipId = readJson(postJson("/api/v1/memberships",
                "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void mappingEnqueuesCreateUserAndUnpaidMembershipDisablesAccess() throws Exception {
        postJson("/api/v1/devices/" + deviceId + "/mappings",
                "{\"memberId\":\"" + memberId + "\",\"deviceUserId\":\"1001\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceUserId").value("1001"))
                .andExpect(jsonPath("$.syncState").value("PENDING"))
                .andExpect(jsonPath("$.enrollmentStatus").value("PENDING_ENROLL"));

        var commands = deviceSyncCommandRepository.findAll();
        assertThat(commands).extracting(c -> c.getType())
                .contains(SyncCommandType.CREATE_USER, SyncCommandType.DISABLE_USER);

        mockMvc.perform(get("/api/v1/devices/" + deviceId + "/health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceConnectionState").value("UNKNOWN"))
                .andExpect(jsonPath("$.gatewayStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.gatewaySessionOnline").value(false))
                .andExpect(jsonPath("$.pendingCommandCount").value(2));
    }

    @Test
    void syncResultMarksSucceededAndDoesNotFabricateWithoutGatewayAck() throws Exception {
        postJson("/api/v1/devices/" + deviceId + "/mappings",
                "{\"memberId\":\"" + memberId + "\",\"deviceUserId\":\"1001\"}")
                .andExpect(status().isCreated());

        var create = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> c.getType() == SyncCommandType.CREATE_USER)
                .findFirst().orElseThrow();
        assertThat(create.getState()).isEqualTo(SyncCommandState.PENDING);

        int dispatched = deviceSyncService.dispatchDue();
        assertThat(dispatched).isZero(); // no WSS session — we do not pretend the device was updated
        assertThat(deviceSyncCommandRepository.findById(create.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.RETRYING);

        gatewayMessageService.process(envelope("SYNC_RESULT", create.getCorrelationId(),
                "{\"ok\":true}"));

        assertThat(deviceSyncCommandRepository.findById(create.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.SUCCEEDED);
        mockMvc.perform(get("/api/v1/members/" + memberId + "/access")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.reason").value("PAYMENT_OVERDUE"));
    }

    @Test
    void attendanceIngestIsIdempotentAndDeniedRaisesSecurityEvent() throws Exception {
        postJson("/api/v1/devices/" + deviceId + "/mappings",
                "{\"memberId\":\"" + memberId + "\",\"deviceUserId\":\"1001\"}")
                .andExpect(status().isCreated());

        String occurred = Instant.parse("2026-09-11T06:00:00Z").toString();
        String event = envelope("DEVICE_EVENT", UUID.randomUUID().toString(),
                "{\"deviceUserId\":\"1001\",\"occurredAt\":\"" + occurred
                        + "\",\"method\":\"FACE\",\"granted\":true,\"recNo\":42}");
        gatewayMessageService.process(event);
        gatewayMessageService.process(event); // duplicate recNo

        mockMvc.perform(get("/api/v1/attendance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].direction").value("IN"))
                .andExpect(jsonPath("$.content[0].result").value("GRANTED"))
                .andExpect(jsonPath("$.content[0].memberLinked").value(true));

        gatewayMessageService.process(envelope("DEVICE_EVENT", UUID.randomUUID().toString(),
                "{\"deviceUserId\":\"1001\",\"occurredAt\":\"" + occurred
                        + "\",\"method\":\"FACE\",\"granted\":false,\"recNo\":43}"));

        mockMvc.perform(get("/api/v1/security-events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ACCESS_DENIED"));
    }

    @Test
    void freezeEnqueuesDisableAndRemoteDoorRequiresConfirmation() throws Exception {
        postJson("/api/v1/devices/" + deviceId + "/mappings",
                "{\"memberId\":\"" + memberId + "\",\"deviceUserId\":\"1001\"}")
                .andExpect(status().isCreated());
        long before = deviceSyncCommandRepository.count();

        postJson("/api/v1/memberships/" + membershipId + "/freeze", null)
                .andExpect(status().isOk());
        assertThat(deviceSyncCommandRepository.count()).isGreaterThan(before);
        assertThat(deviceSyncCommandRepository.findAll())
                .anyMatch(c -> c.getType() == SyncCommandType.DISABLE_USER
                        && c.getMembershipId() != null);

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
        postJson("/api/v1/devices/" + deviceId + "/mappings",
                "{\"memberId\":\"" + memberId + "\",\"deviceUserId\":\"1001\"}")
                .andExpect(status().isCreated());

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
