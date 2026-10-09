package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
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
 * V15: one trusted roster becomes a bootstrap report. Known mappings stay on the desired-state
 * writer. Ambiguous people stay unresolved, and a short list creates nothing.
 */
class V15BootstrapIT extends AbstractIntegrationTest {

    private String token;
    private String gatewayToken;
    private String gatewayPublicId;
    private String flaggedId;
    private Long flagged;
    private String sideId;
    private Long side;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("V15 Gym", "v15-gym");
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
        side = deviceRepository.findByPublicId(sideId).orElseThrow().getId();
    }

    @Test
    void aScriptedRosterConvergesTheMatchAndLeavesTheOtherThreeUnresolved() throws Exception {
        JsonNode asha = createOnReader("Asha", "Shah", "V15-ASHA", "1501", flaggedId);
        JsonNode meera = createOnReader("Meera", "Shah", "V15-MEERA", "1502", flaggedId);
        String ashaId = asha.get("id").asString();
        String meeraId = meera.get("id").asString();
        String ashaDeviceUser = mapping(flagged, ashaId).getDeviceUserId();
        String meeraDeviceUser = mapping(flagged, meeraId).getDeviceUserId();
        long members = memberRepository.count();

        postRoster(flaggedId, 3, """
                [{"deviceUserId":"%s","name":"Asha Shah","deleted":false},\
                {"deviceUserId":"%s","name":"Renamed","deleted":false},\
                {"deviceUserId":"7","name":"Walk In","deleted":false}]
                """.formatted(ashaDeviceUser, meeraDeviceUser));
        postRoster(sideId, 2, """
                [{"deviceUserId":"%s","name":"Door","deleted":false},\
                {"deviceUserId":"9","name":"Meera Shah","deleted":false}]
                """.formatted(ashaDeviceUser));

        JsonNode report = bootstrap();

        JsonNode matching = row(report, "MATCHING", flaggedId, ashaDeviceUser);
        assertThat(matching.get("serverName").asString()).isEqualTo("Asha Shah");
        assertThat(deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, ashaDeviceUser)).isEmpty();
        assertThat(mapping(flagged, ashaId).getDeviceUserId()).isEqualTo(ashaDeviceUser);
        assertThat(projection(flagged, ashaId).getDeviceUserId()).isEqualTo(ashaDeviceUser);

        JsonNode different = row(report, "DIFFERENT", flaggedId, meeraDeviceUser);
        assertThat(different.get("readerName").asString()).isEqualTo("Renamed");
        assertThat(different.get("serverName").asString()).isEqualTo("Meera Shah");
        DeviceReviewItem review = deviceReviewItemRepository
                .findByDeviceIdAndDeviceUserId(flagged, meeraDeviceUser).orElseThrow();
        assertThat(review.getBootstrapRunId()).isEqualTo(report.get("runId").asString());
        assertThat(review.isResolved()).isFalse();
        assertThat(memberRepository.findByPublicId(meeraId).orElseThrow().getFullName()).isEqualTo("Meera Shah");
        assertThat(mapping(flagged, meeraId).getDeviceUserId()).isEqualTo(meeraDeviceUser);

        JsonNode unlinked = row(report, "UNLINKED", flaggedId, "7");
        assertThat(unlinked.get("suggestionMemberId").isNull()).isTrue();
        PendingEnrollment walkIn = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "7")
                .orElseThrow();
        assertThat(walkIn.getBootstrapRunId()).isEqualTo(report.get("runId").asString());
        assertThat(memberRepository.findAll()).extracting(Member::getFullName).doesNotContain("Walk In");

        assertThat(row(report, "COLLISION", flaggedId, ashaDeviceUser).get("readerName").asString()).isEqualTo("Asha Shah");
        assertThat(row(report, "COLLISION", sideId, ashaDeviceUser).get("readerName").asString()).isEqualTo("Door");
        assertThat(mapping(flagged, ashaId).getDeviceUserId()).isEqualTo(ashaDeviceUser);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(side, ashaDeviceUser)).isEmpty();

        JsonNode suggestion = row(report, "UNLINKED", sideId, "9");
        assertThat(suggestion.get("suggestionMemberId").asString()).isEqualTo(meeraId);
        assertThat(mapping(flagged, meeraId).getDeviceUserId()).isEqualTo(meeraDeviceUser);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(side, member(meeraId).getId())).isEmpty();
        assertThat(memberRepository.count()).isEqualTo(members);
    }

    @Test
    void aTrustedEmptyRosterFromTheGatewaySeedsDesiredRevisions() throws Exception {
        JsonNode asha = createOnReader("Asha", "Shah", "V15-LIVE", "1510", flaggedId);
        String ashaId = asha.get("id").asString();
        String ashaDeviceUser = mapping(flagged, ashaId).getDeviceUserId();
        long members = memberRepository.count();

        postRoster(sideId, 0, "[]");

        DesiredMemberProjection seeded = projection(side, ashaId);
        assertThat(seeded.isPresentOnReader()).isTrue();
        assertThat(seeded.getRevision()).isPositive();
        assertThat(mapping(side, ashaId).getDeviceUserId()).isEqualTo(seeded.getDeviceUserId());
        assertThat(mapping(flagged, ashaId).getDeviceUserId()).isEqualTo(ashaDeviceUser);
        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(pendingEnrollmentRepository.findByDeviceId(side)).isEmpty();
        assertThat(deviceReviewItemRepository.findByDeviceId(side)).isEmpty();

        postRoster(sideId, 2, """
                [{"deviceUserId":"8","name":"Short","deleted":false}]
                """);
        assertThat(mapping(side, ashaId).getDeviceUserId()).isEqualTo(seeded.getDeviceUserId());
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(side, "8")).isEmpty();
        assertThat(desiredMemberProjectionRepository.findByDeviceId(side)).hasSize(1);
        assertThat(memberRepository.findAll()).extracting(Member::getFullName).containsExactly("Asha Shah");
    }

    @Test
    void anEmptyReaderIsSeededFromTheServerAndNotFromAnotherReader() throws Exception {
        JsonNode asha = createOnReader("Asha", "Shah", "V15-SEED", "1503", flaggedId);
        String ashaId = asha.get("id").asString();
        String ashaDeviceUser = mapping(flagged, ashaId).getDeviceUserId();
        postRoster(flaggedId, 1, """
                [{"deviceUserId":"12","name":"Door","deleted":false}]
                """);
        postRoster(sideId, 0, "[]");

        JsonNode report = bootstrap();

        JsonNode seeded = row(report, "SEEDED", sideId, projection(side, ashaId).getDeviceUserId());
        assertThat(seeded.get("memberId").asString()).isEqualTo(ashaId);
        assertThat(seeded.get("serverName").asString()).isEqualTo("Asha Shah");
        assertThat(mapping(flagged, ashaId).getDeviceUserId()).isEqualTo(ashaDeviceUser);
        assertThat(mapping(side, ashaId).getDeviceUserId()).isNotEqualTo("12");
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(side, "12")).isEmpty();
        assertThat(memberRepository.findAll()).extracting(Member::getFullName).doesNotContain("Door");
    }

    @Test
    void aShortListCreatesNothing() throws Exception {
        JsonNode asha = createOnReader("Asha", "Shah", "V15-SHORT", "1504", flaggedId);
        String ashaDeviceUser = mapping(flagged, asha.get("id").asString()).getDeviceUserId();
        long members = memberRepository.count();
        long projections = desiredMemberProjectionRepository.count();

        postRoster(sideId, 3, """
                [{"deviceUserId":"8","name":"Short","deleted":false}]
                """);
        JsonNode report = bootstrap();

        assertThat(report.get("rows")).isEmpty();
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(side, "8")).isEmpty();
        assertThat(deviceReviewItemRepository.findByDeviceId(side)).isEmpty();
        assertThat(desiredMemberProjectionRepository.findByDeviceId(side)).isEmpty();
        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(desiredMemberProjectionRepository.count()).isEqualTo(projections);
        assertThat(mapping(flagged, asha.get("id").asString()).getDeviceUserId()).isEqualTo(ashaDeviceUser);
        assertThat(deviceRepository.findByPublicId(sideId).orElseThrow().isRosterTrustedEmpty()).isFalse();
    }

    @Test
    void runningTheReportAgainDoesNotDuplicateAResolvedEnrollment() throws Exception {
        postRoster(flaggedId, 1, """
                [{"deviceUserId":"7","name":"Walk In","deleted":false}]
                """);
        JsonNode first = bootstrap();
        PendingEnrollment opened = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "7")
                .orElseThrow();

        JsonNode second = bootstrap();

        PendingEnrollment again = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "7")
                .orElseThrow();
        assertThat(pendingEnrollmentRepository.findByDeviceId(flagged)).hasSize(1);
        assertThat(again.getPublicId()).isEqualTo(opened.getPublicId());
        assertThat(again.getBootstrapRunId()).isEqualTo(first.get("runId").asString());
        assertThat(again.getBootstrapRunId()).isNotEqualTo(second.get("runId").asString());

        JsonNode decision = decide(opened.getPublicId(), "reject");
        ackAbsence(flaggedId, decision.get("revision").asLong(), "7");
        bootstrap();

        PendingEnrollment resolved = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "7")
                .orElseThrow();
        assertThat(pendingEnrollmentRepository.findByDeviceId(flagged)).hasSize(1);
        assertThat(resolved.getPublicId()).isEqualTo(opened.getPublicId());
        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.getBootstrapRunId()).isEqualTo(opened.getBootstrapRunId());
    }

    @Test
    void theSameNameOnTwoMembersIsNotLinked() throws Exception {
        JsonNode left = createOnReader("Sam", "Lee", "V15-SAM-A", "1505", flaggedId);
        JsonNode right = createOnReader("Sam", "Lee", "V15-SAM-B", "1506", flaggedId);
        String leftUser = mapping(flagged, left.get("id").asString()).getDeviceUserId();
        String rightUser = mapping(flagged, right.get("id").asString()).getDeviceUserId();
        postRoster(sideId, 1, """
                [{"deviceUserId":"4","name":"Sam Lee","deleted":false}]
                """);

        JsonNode report = bootstrap();

        JsonNode unlinked = row(report, "UNLINKED", sideId, "4");
        assertThat(unlinked.get("suggestionMemberId").isNull()).isTrue();
        assertThat(memberRepository.count()).isEqualTo(2);
        assertThat(mapping(flagged, left.get("id").asString()).getDeviceUserId()).isEqualTo(leftUser);
        assertThat(mapping(flagged, right.get("id").asString()).getDeviceUserId()).isEqualTo(rightUser);
        assertThat(memberDeviceMappingRepository.findByDeviceId(side)).isEmpty();
        assertThat(memberRepository.findByPublicId(left.get("id").asString()).orElseThrow().getFullName())
                .isEqualTo("Sam Lee");
    }

    private JsonNode bootstrap() throws Exception {
        return readJson(postJson("/api/v1/reviews/bootstrap", "{}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode decide(String id, String action) throws Exception {
        return readJson(postJson("/api/v1/reviews/" + id + "/" + action, "{}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode row(JsonNode report, String outcome, String deviceId, String deviceUserId) {
        for (JsonNode row : report.get("rows")) {
            if (outcome.equals(row.get("outcome").asString())
                    && deviceId.equals(row.get("deviceId").asString())
                    && deviceUserId.equals(row.get("deviceUserId").asString())) {
                return row;
            }
        }
        throw new AssertionError(outcome + " " + deviceUserId + " missing from " + report);
    }

    private Member member(String publicId) {
        return memberRepository.findByPublicId(publicId).orElseThrow();
    }

    private com.example.gym.device.domain.MemberDeviceMapping mapping(Long deviceId, String memberPublicId) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, member(memberPublicId).getId())
                .orElseThrow();
    }

    private DesiredMemberProjection projection(Long deviceId, String memberPublicId) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, member(memberPublicId).getId())
                .orElseThrow();
    }

    private void postRoster(String devicePublicId, int announcedTotal, String users) throws Exception {
        String payload = "{\"announcedTotal\":%d,\"users\":%s}".formatted(announcedTotal, users);
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, devicePublicId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
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
