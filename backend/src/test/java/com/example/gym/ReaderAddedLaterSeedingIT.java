package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.support.Scans;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A reader added after members exist receives every active member it does not have yet,
 * through the desired-state writer, once it shows a complete list. Existing mappings and people
 * the server does not know stay as they are. A later list writes only what is still missing.
 */
class ReaderAddedLaterSeedingIT extends AbstractIntegrationTest {

    @Autowired
    private DesiredProjectionService desiredProjectionService;

    @Autowired
    private MemberFaceService memberFaceService;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String gatewayToken;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Late Gym", "late-gym");
        createUser(tenant.getId(), "late-admin", "late-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("late-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayId, gateway.get("token").asString());
        createReader("Entrance");
        pool = Executors.newFixedThreadPool(12);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void membersCreatedBeforeTheFirstRosterKeepTheirIdsAndTheRestAreSeededAboveTheReadersIds() throws Exception {
        Member asha = member(create("Asha", "LATE-ASHA", "2101"));
        Member bina = member(create("Bina", "LATE-BINA", "2102"));
        String lateId = createReader("Late");
        Long late = deviceId(lateId);
        Member chen = member(create("Chen", "LATE-CHEN", "2103"));
        assertThat(mapping(late, chen).getDeviceUserId()).isEqualTo("1");
        assertThat(cursor(late).getDesiredRevision()).isEqualTo(1);
        assertThat(memberDeviceMappingRepository.findByDeviceId(late)).hasSize(1);

        postRoster(lateId, 1, """
                [{"deviceUserId":"5","name":"Walk In","deleted":false}]
                """);

        assertThat(mapping(late, chen).getDeviceUserId()).isEqualTo("1");
        assertThat(projection(late, chen).getRevision()).isEqualTo(1);
        assertThat(deviceReviewItemRepository.findByDeviceId(late)).isEmpty();
        assertThat(List.of(mapping(late, asha).getDeviceUserId(), mapping(late, bina).getDeviceUserId()))
                .containsExactlyInAnyOrder("6", "7");
        assertThat(cursor(late).getDesiredRevision()).isEqualTo(3);
        assertWalkInUntouched(late, "5");
        assertThat(memberRepository.count()).isEqualTo(3);

        for (JsonNode item : pull(lateId, 0).get("items")) {
            ack(lateId, item).andExpect(status().isOk());
        }
        assertConverged(late, 3);

        postRoster(lateId, 4, """
                [{"deviceUserId":"1","name":"Chen Shah","deleted":false},\
                {"deviceUserId":"5","name":"Walk In","deleted":false},\
                {"deviceUserId":"%s","name":"Asha Shah","deleted":false},\
                {"deviceUserId":"%s","name":"Bina Shah","deleted":false}]
                """.formatted(mapping(late, asha).getDeviceUserId(), mapping(late, bina).getDeviceUserId()));

        assertConverged(late, 3);
        assertThat(memberDeviceMappingRepository.findByDeviceId(late)).hasSize(3);
        assertThat(deviceReviewItemRepository.findByDeviceId(late)).isEmpty();
        assertWalkInUntouched(late, "5");
    }

    @Test
    void aPartiallyMappedReaderGetsOnlyTheActiveMembersItIsMissing() throws Exception {
        Member bina = member(create("Bina", "PART-BINA", "2201"));
        Member chen = member(create("Chen", "PART-CHEN", "2202"));
        Member removed = member(create("Dev", "PART-DEV", "2203"));
        Member inactive = member(create("Ina", "PART-INA", "2204"));
        inactive.setStatus(MemberStatus.INACTIVE);
        memberRepository.save(inactive);
        Member faceless = saveMember(tenant.getId(), "PART-NOFACE", "Noor", "Shah", "2205");

        String lateId = createReader("Late");
        Long late = deviceId(lateId);
        Member asha = member(create("Asha", "PART-ASHA", "2206"));
        desiredProjectionService.write(removed, device(late), memberFaceRepository
                .findByMemberId(removed.getId()).orElseThrow());
        mockMvc.perform(post("/api/v1/members/" + removed.getPublicId() + "/device-sync/" + lateId + "/remove")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        for (JsonNode item : pull(lateId, 0).get("items")) {
            ack(lateId, item).andExpect(status().isOk());
        }
        String ashaId = mapping(late, asha).getDeviceUserId();
        long ashaRevision = projection(late, asha).getRevision();
        DesiredMemberProjection removedBefore = projection(late, removed);
        assertThat(removedBefore.isPresentOnReader()).isFalse();
        long before = cursor(late).getDesiredRevision();
        assertConverged(late, before);

        postRoster(lateId, 1, """
                [{"deviceUserId":"%s","name":"Asha Shah","deleted":false}]
                """.formatted(ashaId));

        assertThat(mapping(late, asha).getDeviceUserId()).isEqualTo(ashaId);
        assertThat(projection(late, asha).getRevision()).isEqualTo(ashaRevision);
        assertThat(projection(late, removed).isPresentOnReader()).isFalse();
        assertThat(projection(late, removed).getRevision()).isEqualTo(removedBefore.getRevision());
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(late, inactive.getId())).isEmpty();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(late, faceless.getId())).isEmpty();
        assertThat(projection(late, bina).isPresentOnReader()).isTrue();
        assertThat(projection(late, chen).isPresentOnReader()).isTrue();
        assertThat(cursor(late).getDesiredRevision()).isEqualTo(before + 2);
        assertDistinctIds(late);
        assertThat(deviceReviewItemRepository.findByDeviceId(late)).isEmpty();

        postRoster(lateId, 1, """
                [{"deviceUserId":"%s","name":"Asha Shah","deleted":false}]
                """.formatted(ashaId));

        assertThat(cursor(late).getDesiredRevision()).isEqualTo(before + 2);
        assertThat(memberDeviceMappingRepository.findByDeviceId(late)).hasSize(4);
    }

    @Test
    void aNonEmptyRosterKeepsUnknownUsersAndSeedsAboveTheirIdsWithoutLinking() throws Exception {
        Member asha = member(create("Asha", "UNK-ASHA", "2301"));
        Member bina = member(create("Bina", "UNK-BINA", "2302"));
        long members = memberRepository.count();
        String lateId = createReader("Late");
        Long late = deviceId(lateId);

        postRoster(lateId, 2, """
                [{"deviceUserId":"3","name":"Asha Shah","deleted":false},\
                {"deviceUserId":"40","name":"Stranger","deleted":false}]
                """);

        assertThat(mapping(late, bina).getDeviceUserId()).isEqualTo("41");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(late, asha.getId()))
                .as("person 3 may be Asha: a seed would put her on the reader twice")
                .isEmpty();
        assertWalkInUntouched(late, "3");
        assertWalkInUntouched(late, "40");
        assertThat(pendingEnrollmentRepository.findByDeviceId(late))
                .extracting(PendingEnrollment::getDeviceUserId).containsExactlyInAnyOrder("3", "40");
        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(deviceReviewItemRepository.findByDeviceId(late)).isEmpty();
        assertThat(pull(lateId, 0).get("items")).hasSize(1);
        assertThat(cursor(late).getDesiredRevision()).isEqualTo(1);
    }

    @Test
    void rostersRacingCreatesSeedEachMemberOnceWithoutDeadlock() throws Exception {
        member(create("Asha", "RACE-ASHA", "2401"));
        String lateId = createReader("Late");
        Long late = deviceId(lateId);
        int creates = 6;
        int rosters = 3;

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < creates; i++) {
            String code = "RACE-" + i;
            String serial = Integer.toString(2410 + i);
            calls.add(() -> createMemberWithFace(token, """
                    {"firstName":"Racer%s","lastName":"Shah","memberCode":"%s","serialNumber":"%s"}
                    """.formatted(code, code, serial)).andReturn().getResponse().getStatus());
        }
        for (int i = 0; i < rosters; i++) {
            calls.add(() -> postRosterStatus(lateId, 0, "[]"));
        }
        assertThat(runTogether(calls)).containsOnly(200, 201);

        int expected = 1 + creates;
        assertThat(memberDeviceMappingRepository.findByDeviceId(late)).hasSize(expected);
        assertDistinctIds(late);
        List<Long> revisions = desiredMemberProjectionRepository.findByDeviceId(late).stream()
                .map(DesiredMemberProjection::getRevision).toList();
        assertThat(revisions).doesNotHaveDuplicates().allMatch(revision -> revision >= 1 && revision <= expected);
        assertThat(cursor(late).getDesiredRevision()).isEqualTo(expected);
    }


    private void assertWalkInUntouched(Long late, String deviceUserId) {
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(late, deviceUserId)).isEmpty();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndDeviceUserId(late, deviceUserId)).isEmpty();
        PendingEnrollment enrollment = pendingEnrollmentRepository
                .findByDeviceIdAndDeviceUserId(late, deviceUserId).orElseThrow();
        assertThat(enrollment.isResolved()).isFalse();
    }

    private void assertDistinctIds(Long deviceId) {
        List<String> ids = memberDeviceMappingRepository.findByDeviceId(deviceId).stream()
                .map(MemberDeviceMapping::getDeviceUserId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    private void assertConverged(Long deviceId, long revision) {
        ReaderRevision cursor = cursor(deviceId);
        assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(revision);
    }

    private <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }

    private String create(String firstName, String code, String serial) throws Exception {
        return createMemberId(token, """
                {"firstName":"%s","lastName":"Shah","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, code, serial));
    }

    private Member member(String publicId) {
        return memberRepository.findByPublicId(publicId).orElseThrow();
    }

    private Long deviceId(String publicId) {
        return deviceRepository.findByPublicId(publicId).orElseThrow().getId();
    }

    private Device device(Long id) {
        return deviceRepository.findById(id).orElseThrow();
    }

    private ReaderRevision cursor(Long deviceId) {
        return readerRevisionRepository.findByDeviceId(deviceId).orElseThrow();
    }

    private MemberDeviceMapping mapping(Long deviceId, Member member) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private DesiredMemberProjection projection(Long deviceId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private void postRoster(String devicePublicId, int announcedTotal, String users) throws Exception {
        assertThat(postRosterStatus(devicePublicId, announcedTotal, users)).isEqualTo(200);
    }

    private int postRosterStatus(String devicePublicId, int announcedTotal, String users) throws Exception {
        String payload = "{\"announcedTotal\":%d,\"users\":%s}".formatted(announcedTotal, users);
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId,
                UUID.randomUUID(), Scans.stamp(payload));
        return mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode pull(String devicePublicId, long after) throws Exception {
        return readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", devicePublicId)
                        .param("after", Long.toString(after)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions ack(String devicePublicId, JsonNode item) throws Exception {
        String nameEx = item.get("nameEx") == null || item.get("nameEx").isNull()
                ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        boolean present = item.get("present").asBoolean();
        String body = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":%s%s}
                """.formatted(devicePublicId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                present ? item.get("name").asString() : "", nameEx, item.get("userStatus").asInt(),
                present ? item.get("validFrom").asString() : "", present ? item.get("validTo").asString() : "",
                present ? item.get("faceSha256").asString() : "", present,
                present ? "" : ",\"failCode\":\"NO_RECORD\"");
        return mockMvc.perform(post("/internal/gateway/desired/ack")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String createReader(String name) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.40\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private String enroll(String gateway, String enrollmentToken) throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gateway + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("credential").asString();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
