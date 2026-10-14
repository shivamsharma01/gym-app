package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A member who becomes active or gets a face later, and a reader that joins with people on it,
 * reach every reader with a gateway without waiting for another roster. Existing mappings, staff
 * removals included, and people the server does not know stay as they are.
 */
class LateMemberChangesIT extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String gatewayToken;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Late Changes Gym", "late-gym");
        createUser(tenant.getId(), "late-admin", "late-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("late-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayId, gateway.get("token").asString());
        pool = Executors.newFixedThreadPool(12);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void aMemberInactiveWhenAReaderWasSeededReachesEveryReaderOnReactivationWithoutARoster() throws Exception {
        String entranceId = createReader("Entrance");
        Long entrance = deviceId(entranceId);
        Member ria = member(create("Ria", "LATE-REACT", "2101"));
        deactivate(ria);
        String exitId = createReader("Exit");
        Long exit = deviceId(exitId);
        postRoster(exitId, 0, "[]");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(exit, ria.getId())).isEmpty();
        ackAll(entranceId);
        long messagesBefore = gatewayMessageCount();

        mockMvc.perform(post("/api/v1/members/" + ria.getPublicId() + "/reactivate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(gatewayMessageCount()).isEqualTo(messagesBefore);
        DesiredMemberProjection onExit = projection(exit, ria);
        assertThat(onExit.isPresentOnReader()).isTrue();
        assertThat(onExit.getUserStatus()).isZero();
        assertThat(mapping(exit, ria).getDeviceUserId()).isEqualTo("1");
        assertThat(projection(entrance, ria).getUserStatus()).isZero();

        ackAll(entranceId);
        ackAll(exitId);
        assertConverged(entrance);
        assertConverged(exit);
        assertThat(cursor(exit).getAppliedRevision()).isGreaterThanOrEqualTo(projection(exit, ria).getRevision());
    }

    @Test
    void aReactivatedMemberWithAFrozenMembershipIsWrittenDisabled() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "LATE-FROZEN", "2111"));
        LocalDate today = LocalDate.now();
        String planId = readJson(postJson("/api/v1/plans",
                "{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String membership = readJson(postJson("/api/v1/memberships", "{\"memberId\":\"" + ria.getPublicId()
                + "\",\"planId\":\"" + planId + "\",\"startDate\":\"" + today.minusDays(5)
                + "\",\"endDate\":\"" + today.plusDays(25) + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        postJson("/api/v1/payments", "{\"memberId\":\"" + ria.getPublicId() + "\",\"membershipId\":\"" + membership
                + "\",\"amount\":1000.00,\"method\":\"CASH\"}").andExpect(status().isCreated());
        postJson("/api/v1/memberships/" + membership + "/freeze", null).andExpect(status().isOk());
        deactivate(ria);
        String exitId = createReader("Exit");
        Long exit = deviceId(exitId);

        mockMvc.perform(post("/api/v1/members/" + ria.getPublicId() + "/reactivate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        DesiredMemberProjection onExit = projection(exit, ria);
        assertThat(onExit.isPresentOnReader()).isTrue();
        assertThat(onExit.getUserStatus()).isEqualTo(1);
        assertThat(onExit.getValidFrom()).startsWith(today.minusDays(5).toString());
        assertThat(onExit.getValidTo()).startsWith(today.plusDays(25).toString());
    }

    @Test
    void aMemberWithoutAFaceReachesEveryReaderWhenTheyGetOne() throws Exception {
        String entranceId = createReader("Entrance");
        String exitId = createReader("Exit");
        Long entrance = deviceId(entranceId);
        Long exit = deviceId(exitId);
        Member noor = saveMember(tenant.getId(), "LATE-FACE", "Noor", "Shah", "2201");
        Member inactive = saveMember(tenant.getId(), "LATE-FACE-OFF", "Ina", "Shah", "2202");
        deactivate(inactive);

        uploadFace(noor, faceJpeg());
        uploadFace(inactive, faceJpeg());

        assertThat(projection(entrance, noor).isPresentOnReader()).isTrue();
        assertThat(projection(exit, noor).isPresentOnReader()).isTrue();
        assertThat(memberDeviceMappingRepository.findByMemberId(inactive.getId())).isEmpty();
        ackAll(entranceId);
        ackAll(exitId);
        assertConverged(entrance);
        assertConverged(exit);
        long entranceRevision = cursor(entrance).getDesiredRevision();

        uploadFace(noor, blueJpeg());

        assertThat(memberDeviceMappingRepository.findByMemberId(noor.getId())).hasSize(2);
        assertThat(cursor(entrance).getDesiredRevision()).isEqualTo(entranceRevision + 1);
        assertThat(projection(entrance, noor).getDeviceUserId()).isEqualTo(mapping(entrance, noor).getDeviceUserId());
    }

    @Test
    void aStaffRemovedMemberIsNotWrittenAgain() throws Exception {
        String entranceId = createReader("Entrance");
        String exitId = createReader("Exit");
        Long exit = deviceId(exitId);
        Member dev = member(create("Dev", "LATE-REMOVED", "2301"));
        mockMvc.perform(post("/api/v1/members/" + dev.getPublicId() + "/device-sync/" + exitId + "/remove")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        ackAll(exitId);
        DesiredMemberProjection removed = projection(exit, dev);
        assertThat(removed.isPresentOnReader()).isFalse();

        assertThat(reconcile(exitId).get("written").asInt()).isZero();
        postRoster(exitId, 0, "[]");
        uploadFace(dev, blueJpeg());

        assertThat(projection(exit, dev).isPresentOnReader()).isFalse();
        assertThat(projection(exit, dev).getRevision()).isEqualTo(removed.getRevision());
        assertThat(mapping(exit, dev).getDeviceUserId()).isEqualTo(removed.getDeviceUserId());
        assertThat(memberDeviceMappingRepository.findByDeviceId(exit)).hasSize(1);
        assertThat(projection(deviceId(entranceId), dev).isPresentOnReader()).isTrue();
    }

    @Test
    void anUnknownUsersIdIsNotReusedOrLinked() throws Exception {
        String entranceId = createReader("Entrance");
        String exitId = createReader("Exit");
        Long entrance = deviceId(entranceId);
        Long exit = deviceId(exitId);
        postRoster(exitId, 1, """
                [{"deviceUserId":"1","name":"Noor Shah","deleted":false}]
                """);
        Member noor = saveMember(tenant.getId(), "LATE-UNKNOWN", "Noor", "Shah", "2401");
        long members = memberRepository.count();

        uploadFace(noor, faceJpeg());

        assertThat(mapping(exit, noor).getDeviceUserId()).isEqualTo("2");
        assertThat(mapping(entrance, noor).getDeviceUserId()).isEqualTo("1");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(exit, "1")).isEmpty();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndDeviceUserId(exit, "1")).isEmpty();
        PendingEnrollment stranger = pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(exit, "1").orElseThrow();
        assertThat(stranger.isResolved()).isFalse();
        assertThat(stranger.getDecision()).isNull();
        assertThat(memberRepository.count()).isEqualTo(members);
    }

    @Test
    void anUnseenIdThatIsOccupiedIsRetriedOnThatReaderOnly() throws Exception {
        String entranceId = createReader("Entrance");
        String exitId = createReader("Exit");
        Long entrance = deviceId(entranceId);
        Long exit = deviceId(exitId);
        Member noor = saveMember(tenant.getId(), "LATE-OCC", "Noor", "Shah", "2501");
        uploadFace(noor, faceJpeg());
        long revision = projection(entrance, noor).getRevision();
        DesiredMemberProjection exitBefore = projection(exit, noor);

        mockMvc.perform(post("/internal/gateway/desired/occupied")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + entranceId + "\",\"revision\":" + revision
                                + ",\"deviceUserId\":\"1\"}"))
                .andExpect(status().isOk());

        assertThat(mapping(entrance, noor).getDeviceUserId()).isEqualTo("2");
        assertThat(mapping(exit, noor).getDeviceUserId()).isEqualTo("1");
        assertThat(projection(exit, noor).getRevision()).isEqualTo(exitBefore.getRevision());
        assertThat(readerBlockedUserRepository.findByDeviceId(exit)).isEmpty();
        ackAll(entranceId);
        ackAll(exitId);
        assertConverged(entrance);
        assertConverged(exit);
    }

    @Test
    void aReaderAddedWithPeopleOnItReceivesTheExistingMembersFromItsFirstList() throws Exception {
        createReader("Entrance");
        Member asha = member(create("Asha", "LATE-ADD-A", "2601"));
        Member bina = member(create("Bina", "LATE-ADD-B", "2602"));
        String lateId = createReader("Late");
        Long late = deviceId(lateId);
        assertThat(memberDeviceMappingRepository.findByDeviceId(late)).isEmpty();

        postUser(lateId, "9", "Walk In");
        postRoster(lateId, 1, """
                [{"deviceUserId":"9","name":"Walk In","deleted":false}]
                """);

        assertThat(List.of(mapping(late, asha).getDeviceUserId(), mapping(late, bina).getDeviceUserId()))
                .containsExactlyInAnyOrder("10", "11");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(late, "9")).isEmpty();
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(late, "9")).isPresent();
        ackAll(lateId);
        assertConverged(late);
        assertThat(cursor(late).getAppliedRevision()).isEqualTo(2);
    }

    @Test
    void runningTheReconcileTwiceWritesNothingTheSecondTime() throws Exception {
        createReader("Entrance");
        member(create("Asha", "LATE-TWICE-A", "2701"));
        member(create("Bina", "LATE-TWICE-B", "2702"));
        saveMember(tenant.getId(), "LATE-TWICE-NOFACE", "Noor", "Shah", "2703");
        String lateId = createReader("Late");
        Long late = deviceId(lateId);

        assertThat(reconcile(lateId).get("written").asInt()).isEqualTo(2);
        long revision = cursor(late).getDesiredRevision();
        assertThat(reconcile(lateId).get("written").asInt()).isZero();

        assertThat(cursor(late).getDesiredRevision()).isEqualTo(revision).isEqualTo(2);
        assertThat(memberDeviceMappingRepository.findByDeviceId(late)).hasSize(2);
        assertThat(auditLogRepository.findAll())
                .filteredOn(row -> AuditActions.DEVICE_RECONCILE_REQUESTED.equals(row.getAction())
                        && lateId.equals(row.getResourceId()))
                .hasSize(2);
    }

    @Test
    void theStaffActionNeedsAReaderWithAGatewayAndDeviceSync() throws Exception {
        String readerId = createReader("Entrance");
        String loose = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Loose\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.41\",\"port\":37777}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        createUser(tenant.getId(), "late-staff", "late-staff@gym.local", "STAFF");
        String staff = tokenFor("late-staff");

        mockMvc.perform(post("/api/v1/devices/" + loose + "/reconcile").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/devices/" + readerId + "/reconcile").header("Authorization", "Bearer " + staff))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconcilesRacingCreatesAndLateChangesMapEachMemberOnceWithoutDeadlock() throws Exception {
        String entranceId = createReader("Entrance");
        Long entrance = deviceId(entranceId);
        Member ria = member(create("Ria", "LATE-RACE-R", "2801"));
        deactivate(ria);
        Member noor = saveMember(tenant.getId(), "LATE-RACE-F", "Noor", "Shah", "2802");
        String lateId = createReader("Late");
        Long late = deviceId(lateId);
        int creates = 5;

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < creates; i++) {
            String code = "LATE-RACE-" + i;
            String serial = Integer.toString(2810 + i);
            calls.add(() -> createMemberWithFace(token, """
                    {"firstName":"Racer%s","lastName":"Shah","memberCode":"%s","serialNumber":"%s"}
                    """.formatted(code, code, serial)).andReturn().getResponse().getStatus());
        }
        for (int i = 0; i < 3; i++) {
            calls.add(() -> mockMvc.perform(post("/api/v1/devices/" + lateId + "/reconcile")
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
        }
        calls.add(() -> mockMvc.perform(post("/api/v1/members/" + ria.getPublicId() + "/reactivate")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
        calls.add(() -> faceUpload(noor, faceJpeg()).andReturn().getResponse().getStatus());
        calls.add(() -> postRosterStatus(lateId, 0, "[]"));
        assertThat(runTogether(calls)).containsOnly(200, 201);

        int expected = creates + 2;
        for (Long reader : List.of(entrance, late)) {
            List<MemberDeviceMapping> mappings = memberDeviceMappingRepository.findByDeviceId(reader);
            assertThat(mappings).hasSize(expected);
            assertThat(mappings).extracting(MemberDeviceMapping::getMemberId).doesNotHaveDuplicates();
            assertThat(mappings).extracting(MemberDeviceMapping::getDeviceUserId).doesNotHaveDuplicates();
            assertThat(desiredMemberProjectionRepository.findByDeviceId(reader))
                    .extracting(DesiredMemberProjection::getRevision).doesNotHaveDuplicates();
        }
        ackAll(lateId);
        assertConverged(late);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void ackAll(String devicePublicId) throws Exception {
        for (JsonNode item : pull(devicePublicId, 0).get("items")) {
            ack(devicePublicId, item).andExpect(status().isOk());
        }
    }

    private void assertConverged(Long deviceId) {
        ReaderRevision cursor = cursor(deviceId);
        assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision());
    }

    private long gatewayMessageCount() {
        return gatewayMessageDedupeRepository.count();
    }

    private void deactivate(Member member) throws Exception {
        mockMvc.perform(delete("/api/v1/members/" + member.getPublicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    private void uploadFace(Member member, byte[] jpeg) throws Exception {
        faceUpload(member, jpeg).andExpect(status().isOk());
    }

    private ResultActions faceUpload(Member member, byte[] jpeg) throws Exception {
        return mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/members/" + member.getPublicId() + "/face")
                .file(new MockMultipartFile("file", "face.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg))
                .header("Authorization", "Bearer " + token));
    }

    private static byte[] blueJpeg() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private JsonNode reconcile(String devicePublicId) throws Exception {
        return readJson(mockMvc.perform(post("/api/v1/devices/" + devicePublicId + "/reconcile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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

    private ReaderRevision cursor(Long deviceId) {
        return readerRevisionRepository.findByDeviceId(deviceId).orElseThrow();
    }

    private MemberDeviceMapping mapping(Long deviceId, Member member) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private DesiredMemberProjection projection(Long deviceId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private void postUser(String devicePublicId, String deviceUserId, String name) throws Exception {
        assertThat(postMessage(devicePublicId, """
                {"deviceUserId":"%s","name":"%s","isNew":true,"deleted":false}
                """.formatted(deviceUserId, name))).isEqualTo(200);
    }

    private void postRoster(String devicePublicId, int announcedTotal, String users) throws Exception {
        assertThat(postRosterStatus(devicePublicId, announcedTotal, users)).isEqualTo(200);
    }

    private int postRosterStatus(String devicePublicId, int announcedTotal, String users) throws Exception {
        return postMessage(devicePublicId, "{\"announcedTotal\":%d,\"users\":%s}".formatted(announcedTotal, users));
    }

    private int postMessage(String devicePublicId, String payload) throws Exception {
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId,
                UUID.randomUUID(), payload.strip());
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
        var request = post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(body == null ? request : request.content(body));
    }
}
