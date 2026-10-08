package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * V14: a staff decision becomes a desired revision. The existing read-back acknowledgement is the
 * only way that decision is closed. Link and create keep the reader's device user id.
 */
class V14StaffDecisionIT extends AbstractIntegrationTest {

    private String token;
    private String gatewayToken;
    private String gatewayPublicId;
    private String flaggedId;
    private Long flagged;
    private String sideId;
    private Long other;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("V14 Gym", "v14-gym");
        createUser(tenant.getId(), "v1-admin", "v1-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("v1-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayPublicId = readJson(createdGateway).get("id").asString();
        gatewayToken = enroll(gatewayPublicId, readJson(createdGateway).get("token").asString());

        flaggedId = createDevice("Entrance", true);
        flagged = deviceRepository.findByPublicId(flaggedId).orElseThrow().getId();
        sideId = createDevice("Side", true);
        other = deviceRepository.findByPublicId(createDevice("Exit", false)).orElseThrow().getId();
    }

    @Test
    void acceptServerWritesTheServerValueAndAckClosesTheAudit() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V14-ACC", "9101", flaggedId);
        String memberId = created.get("id").asString();
        String deviceUserId = mapping(memberId).getDeviceUserId();
        postReaderEdit(deviceUserId, "Left");
        DeviceReviewItem opened = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, deviceUserId)
                .orElseThrow();

        JsonNode decision = decide(opened.getPublicId(), "accept-server");

        assertThat(decision.get("decision").asString()).isEqualTo("ACCEPT_SERVER");
        assertThat(decision.get("actor").asString()).isEqualTo("v1-admin");
        assertThat(decision.get("priorState").asString()).isEqualTo("Left");
        assertThat(decision.get("chosenState").asString()).isEqualTo("Asha Shah");
        assertThat(decision.get("open").asBoolean()).isTrue();
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo(deviceUserId);
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getFullName()).isEqualTo("Asha Shah");
        DesiredMemberProjection projected = projection(memberId);
        assertThat(projected.getReaderName()).isEqualTo("Asha Shah");
        assertThat(projected.getRevision()).isEqualTo(decision.get("revision").asLong());

        ackDesired(flaggedId);

        DeviceReviewItem closed = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getVerificationError()).isNull();
        assertThat(closed.getActor()).isEqualTo("v1-admin");
        assertThat(readerRevisionRepository.findByDeviceId(flagged).orElseThrow().getAppliedRevision())
                .isEqualTo(closed.getDecisionRevision());
    }

    @Test
    void aFailedReadBackLeavesTheDecisionOpenWithTheVerificationError() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V14-FAIL", "9102", flaggedId);
        String memberId = created.get("id").asString();
        String deviceUserId = mapping(memberId).getDeviceUserId();
        postReaderEdit(deviceUserId, "Left");
        DeviceReviewItem opened = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, deviceUserId)
                .orElseThrow();
        decide(opened.getPublicId(), "accept-server");

        JsonNode page = pull(flaggedId);
        JsonNode item = page.get("items").get(0);
        String ack = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"Nope","nameEx":null,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(flaggedId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                item.get("userStatus").asInt(), item.get("validFrom").asString(),
                item.get("validTo").asString(), item.get("faceSha256").asString());
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ack))
                .andExpect(status().isConflict());

        DeviceReviewItem stillOpen = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(stillOpen.isResolved()).isFalse();
        assertThat(stillOpen.getDecision()).isEqualTo("ACCEPT_SERVER");
        assertThat(stillOpen.getActor()).isEqualTo("v1-admin");
        assertThat(stillOpen.getVerificationError()).contains("Read-back does not match");
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getFullName()).isEqualTo("Asha Shah");
        assertThat(readerRevisionRepository.findByDeviceId(flagged).orElseThrow().getAppliedRevision()).isZero();
    }

    @Test
    void createKeepsTheReaderDeviceUserId() throws Exception {
        String hash = uploadFace();
        postFace("11", "Nila Sen", hash);
        long members = memberRepository.count();
        PendingEnrollment enrollment = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "11")
                .orElseThrow();

        JsonNode decision = decide(enrollment.getPublicId(), "create");

        assertThat(memberRepository.count()).isEqualTo(members + 1);
        assertThat(decision.get("decision").asString()).isEqualTo("CREATE");
        assertThat(decision.get("actor").asString()).isEqualTo("v1-admin");
        String memberId = decision.get("chosenState").asString();
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo("11");
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getFullName()).isEqualTo("Nila Sen");

        postFace("11", "Nila Sen", hash);
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo("11");

        ackDesired(flaggedId);
        PendingEnrollment closed = pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getDecisionRevision()).isEqualTo(projection(memberId).getRevision());
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo("11");
    }

    @Test
    void linkKeepsTheReaderDeviceUserIdAndDoesNotCreateAMember() throws Exception {
        JsonNode existing = createOnReader("Ria", "Shah", "V14-LINK", "9103", sideId);
        String memberId = existing.get("id").asString();
        long members = memberRepository.count();
        postFace("12", "Door", uploadFace());
        PendingEnrollment enrollment = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "12")
                .orElseThrow();

        JsonNode decision = readJson(postJson("/api/v1/reviews/" + enrollment.getPublicId() + "/link",
                "{\"memberId\":\"" + memberId + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(decision.get("decision").asString()).isEqualTo("LINK");
        assertThat(decision.get("chosenState").asString()).isEqualTo(memberId);
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo("12");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(flagged, "1")).isEmpty();
        JsonNode pulled = pull(flaggedId).get("items").get(0);
        assertThat(pulled.get("deviceUserId").asString()).isEqualTo("12");
        assertThat(pulled.get("name").asString()).isEqualTo("Ria Shah");
        assertThat(pulled.get("keepDeviceUserId").asBoolean()).isTrue();

        ackDesired(flaggedId);
        assertThat(pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow().isResolved())
                .isTrue();
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo("12");
    }

    @Test
    void rejectCreatesNoMemberAndRemovesThatDeviceUser() throws Exception {
        postFace("13", "Ghost", uploadFace());
        long members = memberRepository.count();
        long removes = removeCommands();
        PendingEnrollment enrollment = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "13")
                .orElseThrow();

        JsonNode decision = decide(enrollment.getPublicId(), "reject");

        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(flagged, "13")).isEmpty();
        assertThat(decision.get("decision").asString()).isEqualTo("REJECT");
        assertThat(decision.get("chosenState").asString()).isEqualTo("removed");
        assertThat(decision.get("actor").asString()).isEqualTo("v1-admin");
        DesiredMemberProjection absence = desiredMemberProjectionRepository
                .findByDeviceIdAndDeviceUserId(flagged, "13").orElseThrow();
        assertThat(absence.getMemberId()).isNull();
        assertThat(absence.isPresentOnReader()).isFalse();
        assertThat(absence.getDeviceUserId()).isEqualTo("13");
        assertThat(removeCommands()).isEqualTo(removes);

        ackAbsence(flaggedId, absence.getRevision(), "13");
        PendingEnrollment closed = pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(flagged, "13")).isEmpty();
    }

    @Test
    void restoreRepublishesTheServerRecordOnTheSameId() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V14-RES", "9104", flaggedId);
        String memberId = created.get("id").asString();
        String deviceUserId = mapping(memberId).getDeviceUserId();
        postAbsence(deviceUserId);
        DeviceReviewItem opened = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, deviceUserId)
                .orElseThrow();

        JsonNode decision = decide(opened.getPublicId(), "restore");

        assertThat(decision.get("decision").asString()).isEqualTo("RESTORE");
        assertThat(decision.get("priorState").asString()).isEqualTo("absent");
        DesiredMemberProjection projected = projection(memberId);
        assertThat(projected.isPresentOnReader()).isTrue();
        assertThat(projected.getDeviceUserId()).isEqualTo(deviceUserId);
        assertThat(projected.getReaderName()).isEqualTo("Asha Shah");
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);

        ackDesired(flaggedId);
        assertThat(deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow().isResolved()).isTrue();
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo(deviceUserId);
    }

    @Test
    void removeFromThisReaderUsesTheAbsenceWriterAndKeepsTheMember() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V14-REM", "9105", flaggedId);
        String memberId = created.get("id").asString();
        String deviceUserId = mapping(memberId).getDeviceUserId();
        postReaderEdit(deviceUserId, "Left");
        DeviceReviewItem opened = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, deviceUserId)
                .orElseThrow();
        long removes = removeCommands();

        JsonNode decision = decide(opened.getPublicId(), "remove");

        assertThat(decision.get("decision").asString()).isEqualTo("REMOVE");
        assertThat(decision.get("chosenState").asString()).isEqualTo("removed");
        DesiredMemberProjection projected = projection(memberId);
        assertThat(projected.isPresentOnReader()).isFalse();
        assertThat(projected.getDeviceUserId()).isEqualTo(deviceUserId);
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(mapping(memberId).getDeviceUserId()).isEqualTo(deviceUserId);
        assertThat(desiredMemberProjectionRepository.findByDeviceId(other)).isEmpty();
        assertThat(removeCommands()).isEqualTo(removes);

        ackAbsence(flaggedId, projected.getRevision(), deviceUserId);
        DeviceReviewItem closed = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getActor()).isEqualTo("v1-admin");
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void dismissAndRemoveDoNotCloseTheReviewItem() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V14-OLD", "9106", flaggedId);
        String deviceUserId = mapping(created.get("id").asString()).getDeviceUserId();
        postReaderEdit(deviceUserId, "Left");
        DeviceReviewItem opened = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, deviceUserId)
                .orElseThrow();

        mockMvc.perform(post("/api/v1/devices/" + flaggedId + "/conflicts/" + opened.getPublicId() + "/resolve")
                        .param("action", "DISMISS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/devices/" + flaggedId + "/conflicts/" + opened.getPublicId() + "/resolve")
                        .param("action", "REMOVE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        DeviceReviewItem still = deviceReviewItemRepository.findByPublicId(opened.getPublicId()).orElseThrow();
        assertThat(still.isResolved()).isFalse();
        assertThat(still.getDecision()).isNull();
        assertThat(still.getDeviceUserId()).isEqualTo(deviceUserId);
    }

    private JsonNode decide(String id, String action) throws Exception {
        return readJson(postJson("/api/v1/reviews/" + id + "/" + action, "{}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private com.example.gym.device.domain.MemberDeviceMapping mapping(String memberPublicId) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(
                flagged, memberRepository.findByPublicId(memberPublicId).orElseThrow().getId()).orElseThrow();
    }

    private DesiredMemberProjection projection(String memberPublicId) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(
                flagged, memberRepository.findByPublicId(memberPublicId).orElseThrow().getId()).orElseThrow();
    }

    private long removeCommands() {
        return deviceSyncCommandRepository.findAll().stream()
                .filter(command -> command.getType() == SyncCommandType.REMOVE_USER)
                .count();
    }

    private String uploadFace() throws Exception {
        String body = mockMvc.perform(post("/internal/gateway/faces")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(jpeg()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(body).get("sha256").asString();
    }

    private void postFace(String deviceUserId, String name, String faceSha256) throws Exception {
        String payload = """
                {"deviceUserId":"%s","name":"%s","faceSha256":"%s","isNew":true,"deleted":false}
                """.formatted(deviceUserId, name, faceSha256);
        postMessage(payload);
    }

    private void postReaderEdit(String deviceUserId, String name) throws Exception {
        String payload = """
                {"deviceUserId":"%s","name":"%s","authority":"Customer","isNew":false,"deleted":false}
                """.formatted(deviceUserId, name);
        postMessage(payload);
    }

    private void postAbsence(String deviceUserId) throws Exception {
        postMessage("""
                {"deviceUserId":"%s","deleted":true,"isNew":false}
                """.formatted(deviceUserId));
    }

    private void postMessage(String payload) throws Exception {
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, flaggedId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private JsonNode pull(String deviceId) throws Exception {
        return readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", deviceId)
                        .param("after", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private void ackDesired(String deviceId) throws Exception {
        JsonNode item = pull(deviceId).get("items").get(0);
        String nameEx = item.get("nameEx").isNull() ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        String ack = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(deviceId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                item.get("name").asString(), nameEx, item.get("userStatus").asInt(),
                item.get("validFrom").asString(), item.get("validTo").asString(),
                item.get("faceSha256").asString());
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ack))
                .andExpect(status().isOk());
    }

    private void ackAbsence(String deviceId, long revision, String deviceUserId) throws Exception {
        String ack = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"","userStatus":0,\
                "validFrom":"","validTo":"","faceSha256":"","present":false,"failCode":"NO_RECORD"}
                """.formatted(deviceId, revision, deviceUserId);
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ack))
                .andExpect(status().isOk());
    }

    private JsonNode createOnReader(
            String firstName, String lastName, String code, String serial, String readerId) throws Exception {
        String member = """
                {"firstName":"%s","lastName":"%s","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, lastName, code, serial);
        MvcResult result = mockMvc.perform(multipart("/api/v1/members")
                        .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                                member.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("face", "face.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg()))
                        .param("readerId", readerId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(result.getResponse().getContentAsString());
    }

    private String createDevice(String name, boolean projection) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayPublicId + "\",\"projectionEnabled\":" + projection + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String enroll(String gatewayId, String enrollmentToken) throws Exception {
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(enrolled).get("credential").asString();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
