package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.ReaderRevisions;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A scan whose list the server acknowledged no longer sends the per-user observations. The list
 * handler runs the same observation for each entry, so the same report must leave the same rows
 * either way, with every protection intact, and a list racing a member change must not apply or
 * flag stale state.
 */
class V21ListOnlyIT extends AbstractIntegrationTest {

    private static final Pattern VOLATILE = Pattern.compile("^id$|^public_id$|^version$|_at$|_by$|(?<!device_user)_id$");
    private static final List<String> OBSERVED_TABLES = List.of(
            "device_observed_user", "pending_enrollment", "device_review_item", "device_review_snapshot",
            "device_reader_baseline", "member_device_mapping", "desired_member", "reader_revision", "member");

    @Autowired
    private ReaderRevisions readerRevisions;

    @Autowired
    private TransactionTemplate transactions;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String gatewayToken;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void aListAloneLeavesTheSameRowsAsThePerUserMessages() throws Exception {
        Scenario perUser = scenario();
        for (String user : perUser.users()) {
            assertThat(postMessage(perUser.readerId(), user)).isEqualTo(200);
        }
        Map<String, List<String>> afterPerUser = dump();

        Scenario listOnly = scenario();
        assertThat(postMessage(listOnly.readerId(), list(listOnly.users()))).isEqualTo(200);
        Map<String, List<String>> afterList = dump();

        assertThat(afterList).isEqualTo(afterPerUser);
        assertThat(afterList.get("device_observed_user")).hasSize(5);
    }

    @Test
    void aListAloneKeepsUnknownUsersRemovalsAndFrozenMembersAsTheyAre() throws Exception {
        Scenario run = scenario();
        Long reader = deviceId(run.readerId());

        assertThat(postMessage(run.readerId(), list(run.users()))).isEqualTo(200);

        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(reader, "900")).isPresent();
        assertThat(memberDeviceMappingRepository.existsByDeviceIdAndDeviceUserId(reader, "900")).isFalse();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndDeviceUserId(reader, "900")).isEmpty();
        DesiredMemberProjection removed = projection(reader, run.removed());
        assertThat(removed.isPresentOnReader()).isFalse();
        assertThat(removed.getRevision()).isEqualTo(run.removedRevision());
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(reader, removed.getDeviceUserId())).isEmpty();
        assertThat(projection(reader, run.frozen()).getUserStatus()).isEqualTo(1);
        assertThat(deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(reader, mappedId(reader, run.renamed())))
                .isPresent();
        assertThat(deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(reader, mappedId(reader, run.matching())))
                .isEmpty();
        assertThat(memberDeviceMappingRepository.findByDeviceId(reader)).hasSize(4);
    }

    @Test
    void aRenameCommittedWhileAListWaitsForTheReaderIsKeptAndNotFlagged() throws Exception {
        Scenario run = scenario();
        Long reader = deviceId(run.readerId());
        Member asha = run.matching();
        long revision = projection(reader, asha).getRevision();

        Futures racing = holdingReader(reader,
                () -> postMessage(run.readerId(), list(run.users())),
                () -> renameStatus(asha, "Moved"));

        assertThat(racing.first().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(racing.second().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertRenamedAndNotFlagged(reader, asha, revision);
    }

    @Test
    void aRenameQueuedBeforeAListIsKeptAndNotFlagged() throws Exception {
        Scenario run = scenario();
        Long reader = deviceId(run.readerId());
        Member asha = run.matching();
        long revision = projection(reader, asha).getRevision();

        Futures racing = holdingReader(reader,
                () -> renameStatus(asha, "Moved"),
                () -> postMessage(run.readerId(), list(run.users())));

        assertThat(racing.first().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(racing.second().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertRenamedAndNotFlagged(reader, asha, revision);
    }

    @Test
    void aDeactivationRacingAListLeavesTheMemberDisabled() throws Exception {
        Scenario run = scenario();
        Long reader = deviceId(run.readerId());
        Member asha = run.matching();

        Futures racing = holdingReader(reader,
                () -> postMessage(run.readerId(), list(run.users())),
                () -> deactivateStatus(asha));

        assertThat(racing.first().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(racing.second().get(30, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(projection(reader, asha).getUserStatus()).isEqualTo(1);
    }

    // --- scenario --------------------------------------------------------------------------------

    /**
     * One reader with four applied members, then the reader reports: one matching member, one under
     * another name, one staff removed, one frozen, and one person the server does not know.
     */
    private record Scenario(String readerId, List<String> users, Member matching, Member renamed,
                            Member removed, long removedRevision, Member frozen) {
    }

    private Scenario scenario() throws Exception {
        resetDatabase();
        tenant = createTenant("List Gym", "list-gym");
        createUser(tenant.getId(), "list-admin", "list-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("list-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayId, gateway.get("token").asString());
        String readerId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.42\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        Long reader = deviceId(readerId);

        Member asha = member(create("Asha", "LO-1", "4101"));
        Member ravi = member(create("Ravi", "LO-2", "4102"));
        Member dev = member(create("Dev", "LO-3", "4103"));
        Member noor = member(create("Noor", "LO-4", "4104"));
        postJson("/api/v1/memberships/" + paidMembership(noor) + "/freeze", null).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/members/" + dev.getPublicId() + "/device-sync/" + readerId + "/remove")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        ackAll(readerId);

        List<String> users = new ArrayList<>();
        users.add(reported(reader, asha, "Asha Shah", 0));
        users.add(reported(reader, ravi, "Ravi K", 0));
        users.add(reported(reader, dev, "Dev Shah", 0));
        users.add(reported(reader, noor, "Noor Shah", 1));
        users.add("""
                {"deviceUserId":"900","name":"Walk In","userStatus":0,"authority":"Customer","isNew":true,"deleted":false}""");
        return new Scenario(readerId, users, asha, ravi, dev, projection(reader, dev).getRevision(), noor);
    }

    private String reported(Long reader, Member member, String name, int userStatus) {
        DesiredMemberProjection desired = projection(reader, member);
        return """
                {"deviceUserId":"%s","name":"%s","userStatus":%d,"authority":"%s","isNew":true,"deleted":false}"""
                .formatted(desired.getDeviceUserId(), name, userStatus, desired.getAuthority());
    }

    private void assertRenamedAndNotFlagged(Long reader, Member member, long revisionBefore) {
        DesiredMemberProjection desired = projection(reader, member);
        assertThat(desired.getReaderName()).isEqualTo("Moved Shah");
        assertThat(desired.getRevision()).isGreaterThan(revisionBefore);
        assertThat(desired.isPresentOnReader()).isTrue();
        assertThat(deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(reader, desired.getDeviceUserId())).isEmpty();
        assertThat(deviceObservedUserRepository.findByDeviceIdAndDeviceUserId(reader, desired.getDeviceUserId())
                .orElseThrow().getReaderName()).isEqualTo("Asha Shah");
    }

    /** Every observation table, without generated ids, timestamps, or versions, rows sorted. */
    private static Map<String, List<String>> dump() throws Exception {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        try (var connection = rootConnection(); var statement = connection.createStatement()) {
            for (String table : OBSERVED_TABLES) {
                List<String> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData columns = result.getMetaData();
                    while (result.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= columns.getColumnCount(); i++) {
                            String column = columns.getColumnName(i).toLowerCase();
                            if (!VOLATILE.matcher(column).find()) {
                                row.append(column).append('=').append(result.getString(i)).append(' ');
                            }
                        }
                        rows.add(row.toString());
                    }
                }
                rows.sort(null);
                tables.put(table, rows);
            }
        }
        return tables;
    }

    // --- racing ----------------------------------------------------------------------------------

    private record Futures(Future<Integer> first, Future<Integer> second) {
    }

    /** Holds the reader, starts the first call so it waits there, then the second, and releases. */
    private Futures holdingReader(Long device, Callable<Integer> first, Callable<Integer> second) {
        return transactions.execute(status -> {
            readerRevisions.lock(tenant.getId(), device);
            int before = lockWaits();
            Future<Integer> started = pool.submit(first);
            awaitDoneOrLockWaits(started, before + 1);
            int waits = lockWaits();
            Future<Integer> next = pool.submit(second);
            awaitDoneOrLockWaits(next, waits + 1);
            return new Futures(started, next);
        });
    }

    private static int lockWaits() {
        try (var connection = rootConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
            rows.next();
            return rows.getInt(1);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void awaitDoneOrLockWaits(Future<?> call, int waits) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!call.isDone()) {
            if (lockWaits() >= waits) {
                return;
            }
            assertThat(System.nanoTime()).as("call finished or waiting on a lock").isLessThan(deadline);
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String list(List<String> users) {
        return "{\"announcedTotal\":" + users.size() + ",\"users\":[" + String.join(",", users) + "]}";
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

    private void ackAll(String devicePublicId) throws Exception {
        JsonNode items = readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", devicePublicId)
                        .param("after", "0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items");
        for (JsonNode item : items) {
            boolean present = item.get("present").asBoolean();
            String nameEx = item.get("nameEx") == null || item.get("nameEx").isNull()
                    ? "null" : "\"" + item.get("nameEx").asString() + "\"";
            String body = """
                    {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                    "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":%s%s}
                    """.formatted(devicePublicId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                    present ? item.get("name").asString() : "", nameEx, item.get("userStatus").asInt(),
                    present ? item.get("validFrom").asString() : "", present ? item.get("validTo").asString() : "",
                    present ? item.get("faceSha256").asString() : "", present,
                    present ? "" : ",\"failCode\":\"NO_RECORD\"");
            mockMvc.perform(post("/internal/gateway/desired/ack")
                            .header("Authorization", "Bearer " + gatewayToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
    }

    private String paidMembership(Member member) throws Exception {
        LocalDate today = LocalDate.now();
        String planId = readJson(postJson("/api/v1/plans",
                "{\"name\":\"Monthly " + member.getMemberCode() + "\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String membership = readJson(postJson("/api/v1/memberships", "{\"memberId\":\"" + member.getPublicId()
                + "\",\"planId\":\"" + planId + "\",\"startDate\":\"" + today.minusDays(5)
                + "\",\"endDate\":\"" + today.plusDays(25) + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        postJson("/api/v1/payments", "{\"memberId\":\"" + member.getPublicId() + "\",\"membershipId\":\"" + membership
                + "\",\"amount\":1000.00,\"method\":\"CASH\"}").andExpect(status().isCreated());
        return membership;
    }

    private int deactivateStatus(Member member) throws Exception {
        return mockMvc.perform(delete("/api/v1/members/" + member.getPublicId())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private int renameStatus(Member member, String firstName) throws Exception {
        return mockMvc.perform(put("/api/v1/members/" + member.getPublicId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + firstName + "\",\"lastName\":\"Shah\"}"))
                .andReturn().getResponse().getStatus();
    }

    private String create(String firstName, String code, String serial) throws Exception {
        return createMemberId(token, """
                {"firstName":"%s","lastName":"Shah","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, code, serial));
    }

    private String enroll(String gateway, String enrollmentToken) throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gateway + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("credential").asString();
    }

    private Member member(String publicId) {
        return memberRepository.findByPublicId(publicId).orElseThrow();
    }

    private Long deviceId(String publicId) {
        return deviceRepository.findByPublicId(publicId).orElseThrow().getId();
    }

    private String mappedId(Long reader, Member member) {
        return projection(reader, member).getDeviceUserId();
    }

    private DesiredMemberProjection projection(Long deviceId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(body == null ? request : request.content(body));
    }
}
