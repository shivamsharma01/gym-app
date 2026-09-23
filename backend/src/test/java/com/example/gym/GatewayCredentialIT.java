package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
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

    private static String enrollBody(String gatewayId, String enrollmentToken) {
        return "{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\"" + enrollmentToken + "\"}";
    }
}
