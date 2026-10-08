package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.GatewayStatus;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class GatewayCredentialIT extends AbstractIntegrationTest {

    private String staffToken;
    private String gatewayId;
    private String enrollmentToken;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Cred Gym", "cred-gym");
        createUser(tenant.getId(), "cred-admin", "cred-admin@gym.local", "GYM_ADMIN");
        staffToken = tokenFor("cred-admin");

        String created = mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Front desk\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.enrollmentExpiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = readJson(created);
        gatewayId = node.get("id").asString();
        enrollmentToken = node.get("token").asString();
    }

    @Test
    void enrollSucceedsAndEnrollmentCannotBeReused() throws Exception {
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollBody(gatewayId, enrollmentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayId").value(gatewayId))
                .andExpect(jsonPath("$.credential").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String credential = readJson(enrolled).get("credential").asString();
        assertThat(credential).isNotEqualTo(enrollmentToken);

        mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollBody(gatewayId, enrollmentToken)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void invalidEnrollmentTokenIsRejected() throws Exception {
        mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollBody(gatewayId, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rotateKeepsOldCredentialUntilNewOneIsUsed() throws Exception {
        String credentialV1 = enroll(gatewayId, enrollmentToken);

        String rotated = mockMvc.perform(post("/internal/gateway/credentials/rotate")
                        .header("Authorization", "Bearer " + credentialV1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String credentialV2 = readJson(rotated).get("credential").asString();
        assertThat(credentialV2).isNotEqualTo(credentialV1);

        // V1 still works until V2 authenticates (promote).
        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credentialV1))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credentialV2))
                .andExpect(status().isOk());

        // After promote, V1 is rejected.
        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credentialV1))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reissueEnrollmentAllowsReplacementPc() throws Exception {
        enroll(gatewayId, enrollmentToken);

        String reissued = mockMvc.perform(post("/api/v1/gateways/" + gatewayId + "/enrollment")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newEnrollment = readJson(reissued).get("token").asString();

        String credential = enroll(gatewayId, newEnrollment);
        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk());
    }

    @Test
    void listDevicesForEnrolledGateway() throws Exception {
        String credential = enroll(gatewayId, enrollmentToken);
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.1\","
                                + "\"port\":37777,\"gatewayId\":\"" + gatewayId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Entrance"))
                .andExpect(jsonPath("$[0].host").value("10.0.0.1"));
    }

    @Test
    void missingTokenCannotRegisterPollPushOrIngest() throws Exception {
        String register = message(gatewayId, null, "REGISTER_GATEWAY", "{\"agentVersion\":\"1\"}");

        mockMvc.perform(post("/internal/gateway/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(register))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(register))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/gateway/commands"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/gateway/devices"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/gateway/credentials/rotate"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/gateway/faces")
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(new byte[] {1, 2, 3}))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/gateway/faces/missing/1"))
                .andExpect(status().isUnauthorized());

        assertThat(gatewayRepository.findByPublicId(gatewayId).orElseThrow().getStatus())
                .isEqualTo(GatewayStatus.UNKNOWN);
    }

    @Test
    void expiredCredentialIsRejected() throws Exception {
        String credential = enroll(gatewayId, enrollmentToken);
        var gateway = gatewayRepository.findByPublicId(gatewayId).orElseThrow();
        gateway.setTokenExpiresAt(Instant.now().minusSeconds(60));
        gatewayRepository.saveAndFlush(gateway);

        assertRejected(credential);
    }

    @Test
    void credentialWithoutExpiryIsRejected() throws Exception {
        String credential = enroll(gatewayId, enrollmentToken);
        var gateway = gatewayRepository.findByPublicId(gatewayId).orElseThrow();
        gateway.setTokenExpiresAt(null);
        gatewayRepository.saveAndFlush(gateway);

        assertRejected(credential);
    }

    @Test
    void aGymCannotRegisterASecondGateway() throws Exception {
        mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second PC\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void enrollmentIgnoresTheBodyGatewayId() throws Exception {
        OtherGym otherGym = otherGym("side-gym");
        String otherId = otherGym.gatewayId();
        String otherEnrollment = otherGym.enrollmentToken();

        mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollBody(otherId, enrollmentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayId").value(gatewayId));

        enroll(otherId, otherEnrollment);
    }

    @Test
    void messageBodyGatewayIdIsIgnored() throws Exception {
        String credential = enroll(gatewayId, enrollmentToken);
        String otherId = otherGym("lane-gym").gatewayId();

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(otherId, null, "REGISTER_GATEWAY", "{\"agentVersion\":\"9\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REGISTERED"));

        var authenticated = gatewayRepository.findByPublicId(gatewayId).orElseThrow();
        var claimed = gatewayRepository.findByPublicId(otherId).orElseThrow();
        assertThat(authenticated.getStatus()).isEqualTo(GatewayStatus.ONLINE);
        assertThat(authenticated.getAgentVersion()).isEqualTo("9");
        assertThat(claimed.getStatus()).isEqualTo(GatewayStatus.UNKNOWN);
        assertThat(claimed.getAgentVersion()).isNull();
    }

    @Test
    void gatewayCannotReportOrAcknowledgeAnotherGatewaysDevice() throws Exception {
        String credential = enroll(gatewayId, enrollmentToken);
        OtherGym other = otherGym("foreign-gym");
        String otherId = other.gatewayId();
        enroll(otherId, other.enrollmentToken());

        String ownDeviceId = createDevice(staffToken, "Own reader", "10.0.0.8", gatewayId);
        String foreignDeviceId = createDevice(other.staffToken(), "Foreign reader", "10.0.0.9", otherId);
        String planId = readJson(mockMvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + other.staffToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String memberId = readJson(mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + other.staffToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"serialNumber\":\"1001\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(post("/api/v1/memberships")
                        .header("Authorization", "Bearer " + other.staffToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId + "\"}"))
                .andExpect(status().isCreated());

        Long foreignDevicePk = deviceRepository.findByPublicId(foreignDeviceId).orElseThrow().getId();
        var foreignCommand = deviceSyncCommandRepository.findAll().stream()
                .filter(command -> foreignDevicePk.equals(command.getDeviceId()))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(otherId, foreignDeviceId, "DEVICE_STATUS",
                                "{\"connectionState\":\"ONLINE\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ERROR"))
                .andExpect(jsonPath("$.payload.error").value("device is not owned by this gateway"));
        assertThat(deviceRepository.findByPublicId(foreignDeviceId).orElseThrow().getConnectionState())
                .isEqualTo(DeviceConnectionState.UNKNOWN);

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(otherId, foreignDeviceId, "SYNC_RESULT",
                                "{\"ok\":true}", foreignCommand.getCorrelationId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ERROR"))
                .andExpect(jsonPath("$.payload.error").value("device is not owned by this gateway"));
        assertThat(deviceSyncCommandRepository.findById(foreignCommand.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.PENDING);

        String polled = mockMvc.perform(get("/internal/gateway/commands")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        readJson(polled).forEach(node ->
                assertThat(node.path("deviceId").asString()).isNotEqualTo(foreignDeviceId));

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(otherId, ownDeviceId, "DEVICE_STATUS",
                                "{\"connectionState\":\"ONLINE\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ACK"));
        assertThat(deviceRepository.findByPublicId(ownDeviceId).orElseThrow().getConnectionState())
                .isEqualTo(DeviceConnectionState.ONLINE);
    }

    @Test
    void validCredentialCanRegisterPollAndIngestItsOwnDevice() throws Exception {
        String credential = enroll(gatewayId, enrollmentToken);
        String deviceId = createDevice(staffToken, "Entrance", "10.0.0.8", gatewayId);

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(gatewayId, null, "REGISTER_GATEWAY", "{\"agentVersion\":\"1.2.3\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REGISTERED"));
        assertThat(gatewayRepository.findByPublicId(gatewayId).orElseThrow().getStatus())
                .isEqualTo(GatewayStatus.ONLINE);

        mockMvc.perform(get("/internal/gateway/commands")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(gatewayId, deviceId, "DEVICE_STATUS",
                                "{\"connectionState\":\"ONLINE\",\"firmware\":\"1.0\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ACK"));
        assertThat(deviceRepository.findByPublicId(deviceId).orElseThrow().getConnectionState())
                .isEqualTo(DeviceConnectionState.ONLINE);

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(gatewayId, null, "HEARTBEAT", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ACK"));
        assertThat(gatewayRepository.findByPublicId(gatewayId).orElseThrow().getLastHeartbeatAt())
                .isNotNull();
    }

    private void assertRejected(String credential) throws Exception {
        mockMvc.perform(get("/internal/gateway/devices")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/gateway/commands")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(message(gatewayId, null, "REGISTER_GATEWAY", "{\"agentVersion\":\"1\"}")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/gateway/credentials/rotate")
                        .header("Authorization", "Bearer " + credential))
                .andExpect(status().isUnauthorized());
    }

    private OtherGym otherGym(String slug) throws Exception {
        Tenant tenant = createTenant(slug, slug);
        String username = slug + "-admin";
        createUser(tenant.getId(), username, username + "@gym.local", "GYM_ADMIN");
        String staff = tokenFor(username);
        String created = mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", "Bearer " + staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"LAN\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = readJson(created);
        return new OtherGym(staff, node.get("id").asString(), node.get("token").asString());
    }

    private String createDevice(String ownerStaffToken, String name, String host, String ownerGatewayId)
            throws Exception {
        String created = mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ownerStaffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"" + host + "\","
                                + "\"port\":37777,\"gatewayId\":\"" + ownerGatewayId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return readJson(created).get("id").asString();
    }

    private static String message(String bodyGatewayId, String deviceId, String type, String payload) {
        return message(bodyGatewayId, deviceId, type, payload, UUID.randomUUID().toString());
    }

    private static String message(String bodyGatewayId, String deviceId, String type, String payload,
                                  String correlationId) {
        String deviceJson = deviceId == null ? "null" : "\"" + deviceId + "\"";
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":%s,\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), bodyGatewayId, deviceJson, type,
                correlationId, payload);
    }

    private String enroll(String id, String enrollment) throws Exception {
        String body = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enrollBody(id, enrollment)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Instant expiresAt = Instant.parse(readJson(body).get("expiresAt").asString());
        assertThat(expiresAt).isAfter(Instant.now());
        return readJson(body).get("credential").asString();
    }

    private record OtherGym(String staffToken, String gatewayId, String enrollmentToken) {
    }

    private static String enrollBody(String gatewayId, String enrollmentToken) {
        return "{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\"" + enrollmentToken + "\"}";
    }
}
