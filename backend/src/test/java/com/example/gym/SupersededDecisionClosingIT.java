package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A staff decision closes once the reader confirms a later revision of the same user, even
 * when a member change replaced the decision revision before the gateway applied it.
 */
class SupersededDecisionClosingIT extends AbstractIntegrationTest {

    @Autowired
    private DesiredProjectionService desiredProjectionService;

    @Autowired
    private MemberFaceService memberFaceService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String token;
    private String gatewayToken;
    private String gatewayPublicId;
    private String entranceId;
    private Long entrance;
    private String sideId;
    private Long side;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Superseded Gym", "superseded-gym");
        createUser(tenant.getId(), "superseded-admin", "superseded-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("superseded-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayPublicId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayPublicId, gateway.get("token").asString());
        entranceId = createReader("Entrance");
        entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        sideId = createReader("Side");
        side = deviceRepository.findByPublicId(sideId).orElseThrow().getId();
    }

    @Test
    void aReviewDecisionReplacedByARenameClosesWhenTheReaderConfirmsTheRename() throws Exception {
        String memberId = onlyOnReader("Asha", "SUPERSEDED-REV", "9301", entranceId);
        String userId = deviceUserId(memberId, entrance);
        postReaderEdit(entranceId, userId, "Left");
        DeviceReviewItem opened = reviewItem(entrance, userId);
        long decided = decide(opened.getPublicId(), "accept-server").get("revision").asLong();

        rename(memberId, "Asha Renamed");
        long renamed = current(entranceId, userId).get("revision").asLong();
        assertThat(renamed).isGreaterThan(decided);

        ack(entranceId, userId, decided, null).andExpect(status().isConflict());
        DeviceReviewItem stillApplying = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(stillApplying.isResolved()).isFalse();
        assertThat(stillApplying.getDecisionRevision()).isEqualTo(decided);
        assertThat(stillApplying.getVerificationError()).isNull();

        ack(entranceId, userId, renamed, null).andExpect(status().isOk());

        DeviceReviewItem closed = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getDecision()).isEqualTo("ACCEPT_SERVER");
        assertThat(closed.getDecisionRevision()).isEqualTo(decided);
        assertThat(closed.getActor()).isEqualTo("superseded-admin");
        assertThat(appliedRevision(entrance)).isEqualTo(renamed);
        assertThat(decisionAudits(opened.getPublicId())).isEqualTo(1);
        assertThat(openReviews()).noneMatch(id -> id.equals(opened.getPublicId()));

        ack(entranceId, userId, renamed, null).andExpect(status().isOk());
        DeviceReviewItem again = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(again.isResolved()).isTrue();
        assertThat(again.getDecisionRevision()).isEqualTo(decided);
        assertThat(again.getVersion()).isEqualTo(closed.getVersion());
        assertThat(decisionAudits(opened.getPublicId())).isEqualTo(1);
    }

    @Test
    void aLinkedEnrollmentReplacedByARenameClosesWhenTheReaderConfirmsTheRename() throws Exception {
        String memberId = onlyOnReader("Ria", "SUPERSEDED-LINK", "9302", sideId);
        postFace(entranceId, "31", "Door", uploadFace());
        PendingEnrollment enrollment = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(entrance, "31")
                .orElseThrow();
        long linked = readJson(postJson("/api/v1/reviews/" + enrollment.getPublicId() + "/link",
                "{\"memberId\":\"" + memberId + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("revision").asLong();

        rename(memberId, "Ria Renamed");
        long renamed = current(entranceId, "31").get("revision").asLong();
        assertThat(renamed).isGreaterThan(linked);

        ack(entranceId, "31", linked, null).andExpect(status().isConflict());
        assertThat(pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow().isResolved())
                .isFalse();

        ack(entranceId, "31", renamed, null).andExpect(status().isOk());

        PendingEnrollment closed = pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getDecision()).isEqualTo("LINK");
        assertThat(closed.getDecisionRevision()).isEqualTo(linked);
        assertThat(closed.getVerificationError()).isNull();
        assertThat(appliedRevision(entrance)).isEqualTo(renamed);
        assertThat(decisionAudits(enrollment.getPublicId())).isEqualTo(1);
    }

    @Test
    void aFailedReadBackOfTheNewerRevisionLeavesTheReplacedDecisionOpenWithTheError() throws Exception {
        String memberId = onlyOnReader("Nila", "SUPERSEDED-FAIL", "9303", entranceId);
        String userId = deviceUserId(memberId, entrance);
        postReaderEdit(entranceId, userId, "Left");
        DeviceReviewItem opened = reviewItem(entrance, userId);
        long decided = decide(opened.getPublicId(), "accept-server").get("revision").asLong();
        rename(memberId, "Nila Renamed");
        long renamed = current(entranceId, userId).get("revision").asLong();

        ack(entranceId, userId, renamed, "Nope").andExpect(status().isConflict());

        DeviceReviewItem failed = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(failed.isResolved()).isFalse();
        assertThat(failed.getDecisionRevision()).isEqualTo(decided);
        assertThat(failed.getVerificationError()).contains("Read-back does not match");
        assertThat(appliedRevision(entrance)).isLessThan(renamed);

        ack(entranceId, userId, renamed, null).andExpect(status().isOk());
        DeviceReviewItem closed = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getVerificationError()).isNull();
    }

    @Test
    void anOpenDecisionForAnotherDeviceUserOrOnAnotherReaderStaysOpen() throws Exception {
        String renamedMember = onlyOnReader("Asha", "SUPERSEDED-A", "9304", entranceId);
        String renamedUser = deviceUserId(renamedMember, entrance);
        String otherMember = onlyOnReader("Bina", "SUPERSEDED-B", "9305", entranceId);
        String otherUser = deviceUserId(otherMember, entrance);
        String sideMember = onlyOnReader("Chitra", "SUPERSEDED-C", "9306", sideId);
        String sideUser = deviceUserId(sideMember, side);
        postReaderEdit(entranceId, renamedUser, "Left A");
        postReaderEdit(entranceId, otherUser, "Left B");
        postReaderEdit(sideId, sideUser, "Left C");
        DeviceReviewItem first = reviewItem(entrance, renamedUser);
        DeviceReviewItem second = reviewItem(entrance, otherUser);
        DeviceReviewItem onSide = reviewItem(side, sideUser);
        decide(first.getPublicId(), "accept-server");
        long otherDecision = decide(second.getPublicId(), "accept-server").get("revision").asLong();
        decide(onSide.getPublicId(), "accept-server");

        rename(renamedMember, "Asha Renamed");
        long renamed = current(entranceId, renamedUser).get("revision").asLong();
        assertThat(otherDecision).isLessThan(renamed);

        ack(entranceId, renamedUser, renamed, null).andExpect(status().isOk());

        assertThat(deviceReviewItemRepository.findByPublicId(first.getPublicId()).orElseThrow().isResolved()).isTrue();
        DeviceReviewItem otherStillOpen = deviceReviewItemRepository.findByPublicId(second.getPublicId()).orElseThrow();
        assertThat(otherStillOpen.isResolved()).isFalse();
        assertThat(otherStillOpen.getVerificationError()).isNull();
        DeviceReviewItem sideStillOpen = deviceReviewItemRepository.findByPublicId(onSide.getPublicId()).orElseThrow();
        assertThat(sideStillOpen.isResolved()).isFalse();
        assertThat(sideStillOpen.getVerificationError()).isNull();
        assertThat(openReviews()).contains(second.getPublicId(), onSide.getPublicId())
                .doesNotContain(first.getPublicId());
    }

    private DeviceReviewItem reviewItem(Long deviceId, String deviceUserId) {
        return deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId).orElseThrow();
    }

    private long appliedRevision(Long deviceId) {
        return readerRevisionRepository.findByDeviceId(deviceId).orElseThrow().getAppliedRevision();
    }

    private long decisionAudits(String resourceId) {
        return auditLogRepository.findAll().stream()
                .filter(row -> resourceId.equals(row.getResourceId()))
                .filter(row -> "REVIEW_DECIDED".equals(row.getAction()) || "ENROLLMENT_DECIDED".equals(row.getAction()))
                .count();
    }

    private java.util.List<String> openReviews() throws Exception {
        JsonNode open = readJson(mockMvc.perform(get("/api/v1/reviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return StreamSupport.stream(open.spliterator(), false)
                .filter(row -> row.get("open").asBoolean())
                .map(row -> row.get("id").asString())
                .toList();
    }

    private String deviceUserId(String memberPublicId, Long deviceId) {
        Long memberPk = memberRepository.findByPublicId(memberPublicId).orElseThrow().getId();
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, memberPk).orElseThrow()
                .getDeviceUserId();
    }

    private JsonNode decide(String id, String action) throws Exception {
        return readJson(postJson("/api/v1/reviews/" + id + "/" + action, "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void rename(String memberPublicId, String firstName) throws Exception {
        mockMvc.perform(put("/api/v1/members/" + memberPublicId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + firstName + "\",\"lastName\":\"Shah\"}"))
                .andExpect(status().isOk());
    }

    /** The desired item the reader would pull now for this device user. */
    private JsonNode current(String deviceId, String deviceUserId) throws Exception {
        JsonNode page = readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", deviceId)
                        .param("after", "0")
                        .param("limit", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .filter(item -> deviceUserId.equals(item.get("deviceUserId").asString()))
                .findFirst().orElseThrow();
    }

    /**
     * Acknowledges {@code revision} with the current desired record read back, or with {@code name}
     * read back instead when it is set.
     */
    private ResultActions ack(String deviceId, String deviceUserId, long revision, String name) throws Exception {
        JsonNode item = current(deviceId, deviceUserId);
        String nameEx = item.get("nameEx").isNull() ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        String body = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(deviceId, revision, deviceUserId, name == null ? item.get("name").asString() : name,
                name == null ? nameEx : "null", item.get("userStatus").asInt(), item.get("validFrom").asString(),
                item.get("validTo").asString(), item.get("faceSha256").asString());
        return mockMvc.perform(post("/internal/gateway/desired/ack")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String onlyOnReader(String firstName, String code, String serial, String readerId) throws Exception {
        Device device = deviceRepository.findByPublicId(readerId).orElseThrow();
        Member member = saveMember(device.getTenantId(), code, firstName, "Shah", serial);
        desiredProjectionService.write(member, device, memberFaceService.storeUploaded(member, faceJpeg()));
        return member.getPublicId();
    }

    private String uploadFace() throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/faces")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(faceJpeg()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("sha256").asString();
    }

    private void postFace(String deviceId, String deviceUserId, String name, String faceSha256) throws Exception {
        postMessage(deviceId, """
                {"deviceUserId":"%s","name":"%s","faceSha256":"%s","isNew":true,"deleted":false}
                """.formatted(deviceUserId, name, faceSha256));
    }

    private void postReaderEdit(String deviceId, String deviceUserId, String name) throws Exception {
        postMessage(deviceId, """
                {"deviceUserId":"%s","name":"%s","authority":"Customer","isNew":false,"deleted":false}
                """.formatted(deviceUserId, name));
    }

    private void postMessage(String deviceId, String payload) throws Exception {
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, deviceId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private String createReader(String name) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.40\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayPublicId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private String enroll(String gatewayId, String enrollmentToken) throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("credential").asString();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
