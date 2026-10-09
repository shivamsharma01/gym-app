package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
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
 * V16: one member writer while the flag is on. Turning the flag off leaves review rows in place
 * and leaves the command table. An unflagged reader still receives the old command.
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
    void turningTheFlagOffKeepsReviewRowsAndTheCommandTable() throws Exception {
        String deviceId = createDevice("Entrance", true);
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
                        .content(deviceBody("Entrance", false)))
                .andExpect(status().isOk());

        assertThat(deviceRepository.findByPublicId(deviceId).orElseThrow().isProjectionEnabled()).isFalse();
        assertThat(deviceReviewItemRepository.findByPublicId(reviewId)).isPresent();
        assertThat(pendingEnrollmentRepository.findByPublicId(enrollmentId)).isPresent();
        assertThat(deviceSyncCommandRepository.count()).isZero();
    }

    @Test
    void theFlagIsCheckedOnDispatchAndOnIngest() throws Exception {
        String flaggedId = createDevice("Entrance", true);
        String plainId = createDevice("Side", false);
        Long flagged = deviceRepository.findByPublicId(flaggedId).orElseThrow().getId();
        Long plain = deviceRepository.findByPublicId(plainId).orElseThrow().getId();
        Member member = memberRepository.save(new Member(tenantId, "V16-ASHA", "Asha"));
        memberDeviceMappingRepository.save(new MemberDeviceMapping(tenantId, member.getId(), flagged, "1"));

        assertThat(deviceSyncService.enqueue(tenantId, flagged, member.getId(), null,
                SyncCommandType.CREATE_USER, java.util.Map.of("deviceUserId", "1"))).isNull();
        DeviceSyncCommand kept = deviceSyncService.enqueue(tenantId, plain, member.getId(), null,
                SyncCommandType.CREATE_USER, java.util.Map.of("deviceUserId", "1"));
        assertThat(kept).isNotNull();

        DeviceSyncCommand queued = deviceSyncCommandRepository.save(new DeviceSyncCommand(
                tenantId, flagged, member.getId(), null, SyncCommandType.UPDATE_USER,
                "{\"deviceUserId\":\"1\"}", UUID.randomUUID().toString(), 3, Instant.now()));

        var claimed = pollService.claimDue(gatewayRepository.findByPublicId(gatewayPublicId).orElseThrow());
        assertThat(claimed).extracting(row -> row.get("type")).containsExactly("CREATE_USER");
        assertThat(deviceSyncCommandRepository.findById(queued.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.CANCELLED);
        assertThat(deviceSyncCommandRepository.findById(kept.getId()).orElseThrow().getState())
                .isEqualTo(SyncCommandState.DISPATCHED);

        reconciliationService.applyDeviceUserSnapshot(
                deviceRepository.findByPublicId(flaggedId).orElseThrow(),
                jsonMapper.readTree("{\"deviceUsers\":[],\"usersComplete\":true}"));

        assertThat(reconciliationConflictRepository.count()).isZero();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(flagged, member.getId())).isPresent();
    }

    private String createDevice(String name, boolean projection) throws Exception {
        return readJson(postJson("/api/v1/devices", deviceBody(name, projection))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String deviceBody(String name, boolean projection) {
        return "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                + "\"gatewayId\":\"" + gatewayPublicId + "\",\"projectionEnabled\":" + projection + "}";
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
