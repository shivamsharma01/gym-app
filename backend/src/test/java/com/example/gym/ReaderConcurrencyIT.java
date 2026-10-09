package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.ReaderRevisions;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.member.dto.MemberRequests.UpdateMember;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * Concurrent writers on one reader. Every request must succeed, every desired revision must be
 * distinct and gap-free, and every allocated device user id must be distinct.
 */
class ReaderConcurrencyIT extends AbstractIntegrationTest {

    private static final int WRITERS = 6;

    @Autowired
    private DesiredProjectionService desiredProjectionService;

    @Autowired
    private ReaderRevisions readerRevisions;

    @Autowired
    private MemberService memberService;

    @Autowired
    private TransactionTemplate transactions;
    private String token;
    private String gatewayToken;
    private String gatewayId;
    private String readerId;
    private Long reader;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Race Gym", "race-gym");
        createUser(tenant.getId(), "race-admin", "race-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("race-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayId, gateway.get("token").asString());
        readerId = createReader("Entrance", "10.0.0.30");
        reader = deviceRepository.findByPublicId(readerId).orElseThrow().getId();
        pool = Executors.newFixedThreadPool(WRITERS * 2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentCreatesOnAFreshReaderGetDistinctIdsAndRevisions() throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            int n = i;
            calls.add(() -> createStatus("Race" + n, "RACE-C" + n, "7" + (100 + n)));
        }

        assertThat(runTogether(calls)).containsOnly(201);

        assertDistinctIds(WRITERS);
        assertGapFreeRevisions(WRITERS);
    }

    @Test
    void concurrentRenamesOnOneReaderPublishDistinctRevisions() throws Exception {
        List<String> members = createSequentially(WRITERS, "RACE-R");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            String id = members.get(i);
            int n = i;
            calls.add(() -> renameStatus(id, "Renamed" + n));
        }

        assertThat(runTogether(calls)).containsOnly(200);

        assertGapFreeRevisions(WRITERS * 2);
        assertThat(revisionCursorDesired()).isEqualTo(WRITERS * 2L);
    }

    @Test
    void acknowledgementsRacingRenamesBothSucceed() throws Exception {
        List<String> members = createSequentially(WRITERS * 2, "RACE-A");
        List<String> acks = new ArrayList<>();
        for (long revision = 1; revision <= WRITERS; revision++) {
            acks.add(ackFor(revision));
        }
        List<Callable<Integer>> calls = new ArrayList<>();
        for (String ack : acks) {
            calls.add(() -> ackStatus(ack));
        }
        for (int i = WRITERS; i < WRITERS * 2; i++) {
            String id = members.get(i);
            int n = i;
            calls.add(() -> renameStatus(id, "Renamed" + n));
        }

        assertThat(runTogether(calls)).containsOnly(200);

        assertGapFreeRevisions(WRITERS * 3);
        assertThat(revisionCursorDesired()).isEqualTo(WRITERS * 3L);
        assertThat(readerRevisionRepository.findByDeviceId(reader).orElseThrow().getAppliedRevision())
                .isEqualTo(WRITERS);
    }

    @Test
    void anOccupiedRetryRacingCreatesAllocatesDistinctIds() throws Exception {
        createSequentially(1, "RACE-O");
        String occupiedId = memberDeviceMappingRepository.findByDeviceId(reader).get(0).getDeviceUserId();
        String occupied = """
                {"deviceId":"%s","revision":1,"deviceUserId":"%s"}
                """.formatted(readerId, occupiedId);
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(() -> mockMvc.perform(post("/internal/gateway/desired/occupied")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON).content(occupied))
                .andReturn().getResponse().getStatus());
        for (int i = 0; i < WRITERS; i++) {
            int n = i;
            calls.add(() -> createStatus("Race" + n, "RACE-OC" + n, "7" + (300 + n)));
        }

        List<Integer> statuses = runTogether(calls);

        assertThat(statuses.subList(1, statuses.size())).containsOnly(201);
        assertThat(statuses.get(0)).isEqualTo(200);
        assertDistinctIds(WRITERS + 1);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(reader, occupiedId)).isEmpty();
        assertThat(readerBlockedUserRepository.findByDeviceId(reader))
                .extracting(row -> row.getDeviceUserId()).containsExactly(occupiedId);
        assertGapFreeRevisions(WRITERS + 2);
    }

    @Test
    void renamesOfMembersMappedInOppositeReaderOrderDoNotDeadlockOrLoseUpdates() throws Exception {
        String exitId = createReader("Exit", "10.0.0.31");
        Long exit = deviceRepository.findByPublicId(exitId).orElseThrow().getId();
        assertThat(reader).isLessThan(exit);
        String forward = createOn(readerId, "Fwd", "RACE-F", "8500");
        String backward = createOn(exitId, "Bwd", "RACE-B", "8501");
        alsoMap(forward, exit);
        alsoMap(backward, reader);
        assertThat(mappedReaders(forward)).containsExactly(reader, exit);
        assertThat(mappedReaders(backward)).containsExactly(exit, reader);
        int rounds = 10;

        List<List<Integer>> results = runTogether(List.<Callable<List<Integer>>>of(
                () -> renameRounds(forward, "Fwd", rounds),
                () -> renameRounds(backward, "Bwd", rounds)));

        assertThat(results.get(0)).containsOnly(200);
        assertThat(results.get(1)).containsOnly(200);
        for (Long device : List.of(reader, exit)) {
            assertThat(projection(device, forward).getReaderName()).isEqualTo("Fwd" + (rounds - 1) + " Shah");
            assertThat(projection(device, backward).getReaderName()).isEqualTo("Bwd" + (rounds - 1) + " Shah");
            List<Long> revisions = desiredMemberProjectionRepository.findByDeviceId(device).stream()
                    .map(DesiredMemberProjection::getRevision).toList();
            long desired = readerRevisionRepository.findByDeviceId(device).orElseThrow().getDesiredRevision();
            assertThat(revisions).doesNotHaveDuplicates();
            assertThat(desired).isEqualTo(2 + 2L * rounds);
            assertThat(revisions).contains(desired);
        }
    }

    @Test
    void aRenameRacingAnAckOfTheOlderRevisionNeverLosesOrRegressesState() throws Exception {
        int ackFirst = 0;
        int ackSuperseded = 0;
        for (int i = 0; i < WRITERS; i++) {
            String member = createOn(readerId, "Old" + i, "RACE-S" + i, "8" + (600 + i));
            long older = projection(reader, member).getRevision();
            String staleAck = ackFor(older);
            long appliedBefore = applied();
            String newName = "New" + i;

            List<String> outcomes = runTogether(List.<Callable<String>>of(
                    () -> Integer.toString(renameStatus(member, newName)),
                    () -> ackOutcome(staleAck)));

            assertThat(outcomes.get(0)).isEqualTo("200");
            DesiredMemberProjection renamed = projection(reader, member);
            assertThat(renamed.getReaderName()).isEqualTo(newName + " Shah");
            assertThat(renamed.getRevision()).isGreaterThan(older);
            if (outcomes.get(1).startsWith("200 ")) {
                ackFirst++;
                assertThat(applied()).isEqualTo(older);
            } else {
                ackSuperseded++;
                assertThat(outcomes.get(1)).startsWith("409 ").contains("Revision is not the desired member");
                assertThat(applied()).isEqualTo(appliedBefore);
            }

            long appliedAfterRace = applied();
            assertThat(ackOutcome(staleAck)).startsWith("409 ").contains("Revision is not the desired member");
            assertThat(applied()).isEqualTo(appliedAfterRace);

            String currentAck = ackFor(renamed.getRevision());
            assertThat(ackStatus(currentAck)).isEqualTo(200);
            assertThat(ackStatus(currentAck)).isEqualTo(200);
            assertThat(applied()).isEqualTo(renamed.getRevision());
            assertThat(ackOutcome(staleAck)).startsWith("409 ");
            assertThat(applied()).isEqualTo(renamed.getRevision());
        }
        assertThat(ackFirst + ackSuperseded).isEqualTo(WRITERS);
    }

    @Test
    void anAckBlockedBehindARenameOfTheSameRowIsRefusedAsSuperseded() throws Exception {
        String member = createOn(readerId, "Wait", "RACE-W", "8700");
        long older = projection(reader, member).getRevision();
        String staleAck = ackFor(older);
        Long tenantId = memberRepository.findByPublicId(member).orElseThrow().getTenantId();
        List<Future<String>> ack = new ArrayList<>();

        transactions.executeWithoutResult(status -> {
            readerRevisions.lock(tenantId, reader);
            ack.add(pool.submit(() -> ackOutcome(staleAck)));
            awaitOneLockWait();
            memberService.update(member,
                    new UpdateMember("Moved", "Shah", null, null, null, null, null, null, null), tenantId);
        });

        assertThat(ack.get(0).get(30, TimeUnit.SECONDS))
                .startsWith("409 ").contains("Revision is not the desired member");
        DesiredMemberProjection moved = projection(reader, member);
        assertThat(moved.getReaderName()).isEqualTo("Moved Shah");
        assertThat(moved.getRevision()).isGreaterThan(older);
        assertThat(applied()).isZero();

        String currentAck = ackFor(moved.getRevision());
        assertThat(ackStatus(currentAck)).isEqualTo(200);
        assertThat(ackStatus(currentAck)).isEqualTo(200);
        assertThat(ackOutcome(staleAck)).startsWith("409 ");
        assertThat(applied()).isEqualTo(moved.getRevision());
    }

    private static void awaitOneLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (var connection = rootConnection(); var statement = connection.createStatement()) {
            while (true) {
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM information_schema.processlist
                        WHERE command = 'Query' AND info LIKE 'select % from reader_revision % for update%'""")) {
                    rows.next();
                    if (rows.getInt(1) > 0) {
                        return;
                    }
                }
                assertThat(System.nanoTime()).as("acknowledgement waiting on the reader lock").isLessThan(deadline);
                Thread.sleep(20);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<Integer> renameRounds(String memberId, String prefix, int rounds) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            statuses.add(renameStatus(memberId, prefix + round));
        }
        return statuses;
    }

    private void alsoMap(String memberPublicId, Long deviceId) {
        Member member = memberRepository.findByPublicId(memberPublicId).orElseThrow();
        desiredProjectionService.write(member, deviceRepository.findById(deviceId).orElseThrow(),
                memberFaceRepository.findByMemberId(member.getId()).orElseThrow());
    }

    private List<Long> mappedReaders(String memberPublicId) {
        Long memberId = memberRepository.findByPublicId(memberPublicId).orElseThrow().getId();
        return memberDeviceMappingRepository.findByMemberId(memberId).stream()
                .map(MemberDeviceMapping::getDeviceId).toList();
    }

    private DesiredMemberProjection projection(Long deviceId, String memberPublicId) {
        Long memberId = memberRepository.findByPublicId(memberPublicId).orElseThrow().getId();
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, memberId).orElseThrow();
    }

    private long applied() {
        return readerRevisionRepository.findByDeviceId(reader).orElseThrow().getAppliedRevision();
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
        List<T> statuses = new ArrayList<>();
        for (Future<T> future : futures) {
            statuses.add(future.get(60, TimeUnit.SECONDS));
        }
        return statuses;
    }

    private void assertDistinctIds(int expected) {
        List<String> ids = memberDeviceMappingRepository.findByDeviceId(reader).stream()
                .map(MemberDeviceMapping::getDeviceUserId).toList();
        assertThat(ids).hasSize(expected).doesNotHaveDuplicates();
        List<String> projected = desiredMemberProjectionRepository.findByDeviceId(reader).stream()
                .map(DesiredMemberProjection::getDeviceUserId).toList();
        assertThat(projected).containsExactlyInAnyOrderElementsOf(ids);
    }

    /** Every revision bumped is either still on a row or was superseded on that row; none is shared. */
    private void assertGapFreeRevisions(int bumps) {
        List<Long> revisions = desiredMemberProjectionRepository.findByDeviceId(reader).stream()
                .map(DesiredMemberProjection::getRevision).toList();
        assertThat(revisions).doesNotHaveDuplicates();
        assertThat(revisions).allMatch(revision -> revision >= 1 && revision <= bumps);
        assertThat(revisionCursorDesired()).isEqualTo(bumps);
        assertThat(revisions.stream().mapToLong(Long::longValue).max().orElse(0)).isEqualTo(bumps);
    }

    private long revisionCursorDesired() {
        return readerRevisionRepository.findByDeviceId(reader).orElseThrow().getDesiredRevision();
    }

    private List<String> createSequentially(int count, String codePrefix) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createOn(readerId, "Seq" + i, codePrefix + i, "8" + (100 + i)));
        }
        return ids;
    }

    private String createOn(String onReader, String firstName, String code, String serial) throws Exception {
        String body = mockMvc.perform(createRequest(onReader, firstName, code, serial))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return readJson(body).get("id").asString();
    }

    private int createStatus(String firstName, String code, String serial) throws Exception {
        return mockMvc.perform(createRequest(readerId, firstName, code, serial)).andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.RequestBuilder createRequest(
            String onReader, String firstName, String code, String serial) throws Exception {
        String member = """
                {"firstName":"%s","lastName":"Shah","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, code, serial);
        return multipart("/api/v1/members")
                .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                        member.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("face", "face.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg()))
                .param("readerId", onReader)
                .header("Authorization", "Bearer " + token);
    }

    private int renameStatus(String memberId, String firstName) throws Exception {
        return mockMvc.perform(put("/api/v1/members/" + memberId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + firstName + "\",\"lastName\":\"Shah\"}"))
                .andReturn().getResponse().getStatus();
    }

    private String ackFor(long revision) throws Exception {
        JsonNode item = readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", readerId)
                        .param("after", Long.toString(revision - 1))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items").get(0);
        assertThat(item.get("revision").asLong()).isEqualTo(revision);
        String nameEx = item.get("nameEx").isNull() ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        return """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(readerId, revision, item.get("deviceUserId").asString(),
                item.get("name").asString(), nameEx, item.get("userStatus").asInt(),
                item.get("validFrom").asString(), item.get("validTo").asString(),
                item.get("faceSha256").asString());
    }

    private int ackStatus(String ack) throws Exception {
        return Integer.parseInt(ackOutcome(ack).split(" ", 2)[0]);
    }

    /** Status, then the response body. */
    private String ackOutcome(String ack) throws Exception {
        var response = mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ack))
                .andReturn().getResponse();
        return response.getStatus() + " " + response.getContentAsString();
    }

    private String createReader(String name, String host) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"" + host + "\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
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
