package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * A reader report is an observation. It does not rename the member, create a member, or write
 * another reader.
 */
class FaceSyncIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    private String token;
    private String gatewayId;
    private String entranceId;
    private Long entrance;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Face Gym", "face-gym");
        createUser(tenant.getId(), "face-admin", "face-admin@face.local", "GYM_ADMIN");
        token = tokenFor("face-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN-face\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayId = readJson(createdGateway).get("id").asString();
        mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + readJson(createdGateway).get("token").asString() + "\"}"))
                .andExpect(status().isOk());

        entranceId = createDevice("Entrance");
        createDevice("Exit");
        entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
    }

    @Test
    void aReaderRenameDoesNotChangeTheMemberOrAnotherReader() {
        Member member = memberRepository.save(new Member(deviceRepository.findByPublicId(entranceId)
                .orElseThrow().getTenantId(), "FACE-1", "Asha"));
        memberDeviceMappingRepository.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), entrance, "1"));

        gatewayMessageService.process(envelope(entranceId, """
                {"deviceUserId":"1","name":"Someone Else","frozen":false,"deviceChangedAt":"%s",
                 "isNew":false,"nameChanged":true,"profileChanged":true,"frozenChanged":false,
                 "validityChanged":false,"faceChanged":false,"faceRemoved":false,"deleted":false}
                """.formatted(Instant.now())), gatewayId);

        assertThat(memberRepository.findById(member.getId()).orElseThrow().getFirstName()).isEqualTo("Asha");
        assertThat(memberDeviceMappingRepository.findAll()).hasSize(1);
        assertThat(deviceSyncCommandRepository.findAll()).extracting(c -> c.getType())
                .doesNotContain(SyncCommandType.UPDATE_USER, SyncCommandType.CREATE_USER);
    }

    @Test
    void anUnknownReaderPersonDoesNotBecomeAMember() {
        gatewayMessageService.process(envelope(entranceId, """
                {"deviceUserId":"9","name":"Walk In","frozen":false,"deviceChangedAt":"%s",
                 "isNew":true,"nameChanged":true,"profileChanged":true,"deleted":false}
                """.formatted(Instant.now())), gatewayId);

        assertThat(memberRepository.findAll()).isEmpty();
    }

    private String createDevice(String name) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String envelope(String devicePublicId, String payload) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId,
                UUID.randomUUID(), payload);
    }
}
