package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DeviceReconciliationService;
import com.example.gym.device.DeviceSyncService;
import com.example.gym.device.GatewayCommandPollService;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.domain.SyncCommandState;
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
 * Member state is a desired revision on every reader. Review rows stay. A member command is not
 * dispatched. An empty user list does not remove a mapping. A door command still dispatches.
 */
class V16OneWriterIT extends AbstractIntegrationTest {

    @Autowired
    private DeviceSyncService deviceSyncService;

    @Autowired
    private GatewayCommandPollService pollService;

    @Autowired
    private DeviceReconciliationService reconciliationService;

    private String token;
    private String gatewayPublicId;
    private Long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("V16 Gym", "v16-gym");
        tenantId = tenant.getId();
        createUser(tenant.getId(), "v1-admin", "v1-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("v1-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayPublicId = readJson(createdGateway).get("id").asString();
    }

    @Test
    void updatingAReaderKeepsReviewRows() throws Exception {
        String deviceId = createDevice("Entrance");
        Long device = deviceRepository.findByPublicId(deviceId).orElseThrow().getId();
        Member member = memberRepository.save(new Member(tenantId, "V16-ASHA", "Asha"));

        DeviceReviewItem review = new DeviceReviewItem(tenantId, device, member.getId(), "1");
        review.setObservedAt(Instant.now());
        review.setReaderName("Left");
        review.setServerName("Asha");
        String reviewId = deviceReviewItemRepository.save(review).getPublicId();

        PendingEnrollment enrollment = new PendingEnrollment(tenantId, device, "7");
        enrollment.setObservedAt(Instant.now());
        String enrollmentId = pendingEnrollmentRepository.save(enrollment).getPublicId();

        mockMvc.perform(put("/api/v1/devices/" + deviceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deviceBody("Entrance")))
                .andExpect(status().isOk());

        assertThat(deviceRepository.findByPublicId(deviceId)).isPresent();
        assertThat(deviceReviewItemRepository.findByPublicId(reviewId)).isPresent();
        assertThat(pendingEnrollmentRepository.findByPublicId(enrollmentId)).isPresent();
        assertThat(deviceSyncCommandRepository.count()).isZero();
    }

    @Test
    void memberCommandsAreNotDispatchedAndAnEmptyListDoesNotDropAMapping() throws Exception {
        String entranceId = createDevice("Entrance");
        String sideId = createDevice("Side");
        Long entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        Long side = deviceRepository.findByPublicId(sideId).orElseThrow().getId();
        Member member = memberRepository.save(new Member(tenantId, "V16-ASHA", "Asha"));
        memberDeviceMappingRepository.save(new MemberDeviceMapping(tenantId, member.getId(), entrance, "1"));

        assertThatThrownBy(() -> deviceSyncService.enqueue(tenantId, entrance, member.getId(), null,
                SyncCommandType.CREATE_USER, java.util.Map.of("deviceUserId", "1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deviceSyncService.enqueue(tenantId, side, member.getId(), null,
                SyncCommandType.CREATE_USER, java.util.Map.of("deviceUserId", "1")))
                .isInstanceOf(IllegalArgumentException.class);

        DeviceSyncCommand queued = deviceSyncCommandRepository.save(new DeviceSyncCommand(
                tenantId, entrance, member.getId(), null, SyncCommandType.UPDATE_USER,
                "{\"deviceUserId\":\"1\"}", UUID.randomUUID().toString(), 3, Instant.now()));
        DeviceSyncCommand door = deviceSyncCommandRepository.save(new DeviceSyncCommand(
                tenantId, side, null, null, SyncCommandType.OPEN_DOOR,
                "{}", UUID.randomUUID().toString(), 3, Instant.now()));

        var claimed = pollService.claimDue(gatewayRepository.findByPublicId(gatewayPublicId).orElseThrow());
        assertThat(claimed).extracting(row -> row.get("type")).containsExactly("OPEN_DOOR");
        assertThat(deviceSyncCommandRepository.findById(queued.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.CANCELLED);
        assertThat(deviceSyncCommandRepository.findById(door.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.DISPATCHED);

        reconciliationService.applyDeviceUserSnapshot(
                deviceRepository.findByPublicId(entranceId).orElseThrow(),
                jsonMapper.readTree("{\"deviceUsers\":[],\"usersComplete\":true}"));

        assertThat(reconciliationConflictRepository.count()).isZero();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(entrance, member.getId())).isPresent();
    }

    private String createDevice(String name) throws Exception {
        return readJson(postJson("/api/v1/devices", deviceBody(name))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String deviceBody(String name) {
        return "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                + "\"gatewayId\":\"" + gatewayPublicId + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
