package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * One trusted roster becomes a bootstrap report. Known mappings stay on the desired-state
 * writer. Ambiguous people stay unresolved, and a short list creates nothing.
 */
class RosterBootstrapReportIT extends AbstractIntegrationTest {

    @Autowired
    private DesiredProjectionService desiredProjectionService;

    @Autowired
    private MemberFaceService memberFaceService;

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
        Tenant tenant = createTenant("Bootstrap Gym", "bootstrap-gym");
        createUser(tenant.getId(), "bootstrap-admin", "bootstrap-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("bootstrap-admin");

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
        JsonNode asha = onlyOnReader("Asha", "Shah", "BOOT-ASHA", "1501", flaggedId);
        JsonNode meera = onlyOnReader("Meera", "Shah", "BOOT-MEERA", "1502", flaggedId);
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
        assertThat(mapping(side, meeraId).getDeviceUserId()).isNotEqualTo("9");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(side, "9")).isEmpty();
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(side, "9").orElseThrow().isResolved())
                .isFalse();
        assertThat(memberRepository.count()).isEqualTo(members);
    }

    @Test
    void aTrustedEmptyRosterFromTheGatewaySeedsDesiredRevisions() throws Exception {
        JsonNode asha = onlyOnReader("Asha", "Shah", "BOOT-LIVE", "1510", flaggedId);
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
        JsonNode asha = onlyOnReader("Asha", "Shah", "BOOT-SEED", "1503", flaggedId);
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
        JsonNode asha = onlyOnReader("Asha", "Shah", "BOOT-SHORT", "1504", flaggedId);
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
        JsonNode left = onlyOnReader("Sam", "Lee", "BOOT-SAM-A", "1505", flaggedId);
        JsonNode right = onlyOnReader("Sam", "Lee", "BOOT-SAM-B", "1506", flaggedId);
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
        assertThat(memberDeviceMappingRepository.findByDeviceId(side))
                .extracting(com.example.gym.device.domain.MemberDeviceMapping::getDeviceUserId)
                .hasSize(2)
                .doesNotContain("4");
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(side, "4").orElseThrow().isResolved())
                .isFalse();
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

    /** On this one reader only, as when the other readers were added after the member was created. */
    private JsonNode onlyOnReader(String firstName, String lastName, String code, String serial, String readerId)
            throws Exception {
        Device device = deviceRepository.findByPublicId(readerId).orElseThrow();
        Member member = saveMember(device.getTenantId(), code, firstName, lastName, serial);
        desiredProjectionService.write(member, device, memberFaceService.storeUploaded(member, faceJpeg()));
        return jsonMapper.createObjectNode().put("id", member.getPublicId());
    }

    private String createDevice(String name, boolean projection) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayPublicId + "\"}")
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

}
