package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.Device;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.support.Scans;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Measures the server cost of a full trusted list on the test database: one reader with every
 * active member already mapped, so a list seeds nothing. Not part of the normal run; enable with
 * {@code -Dgym.measure=true}. The figures are printed with the prefix {@code ROSTER-LOAD}.
 */
@EnabledIfSystemProperty(named = "gym.measure", matches = "true")
class RosterScanLoadIT extends AbstractIntegrationTest {

    private static final int USERS = Integer.getInteger("gym.measure.users", 1200);
    private static final int ROUNDS = 3;

    @Autowired
    private MemberFaceService memberFaceService;

    @Autowired
    private DesiredProjectionService desiredProjectionService;

    private String token;
    private String gatewayToken;
    private String gatewayId;

    @Test
    void aFullListOfUndecidedPeople() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Load Gym", "load-gym");
        createUser(tenant.getId(), "load-admin", "load-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("load-admin");
        String readerId = gatewayReader();
        List<String[]> ids = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            ids.add(new String[] {Integer.toString(i + 1), "Person" + i + " Shah"});
        }
        String whole = "{\"announcedTotal\":" + USERS + ",\"users\":" + list(ids) + "}";
        for (int round = 0; round < 2; round++) {
            Measured list = measure(() -> assertThat(send(readerId, whole)).isEqualTo(200));
            report("undecided list round=%d users=%d ms=%d statements=%d readerLockMs=%d longestHoldMs=%d",
                    round, USERS, list.millis(), list.statements(), list.lockMillis(), list.longestHoldMillis());
        }
    }

    private String gatewayReader() throws Exception {
        JsonNode gateway = readJson(mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"LAN\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = readJson(mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + gateway.get("token").asString() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("credential").asString();
        return readJson(mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.50\",\"port\":37777,"
                                + "\"gatewayId\":\"" + gatewayId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    @Test
    void aFullListOfAMappedReader() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Load Gym", "load-gym");
        createUser(tenant.getId(), "load-admin", "load-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("load-admin");
        JsonNode gateway = readJson(mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"LAN\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = readJson(mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + gateway.get("token").asString() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("credential").asString();
        String readerId = readJson(mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.50\",\"port\":37777,"
                                + "\"gatewayId\":\"" + gatewayId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        Device reader = deviceRepository.findByPublicId(readerId).orElseThrow();

        long setup = System.nanoTime();
        byte[] jpeg = faceJpeg();
        List<String[]> ids = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            Member member = saveMember(tenant.getId(), "LOAD-" + i, "Load" + i, "Shah", Integer.toString(50000 + i));
            MemberFace face = memberFaceService.storeUploaded(member, jpeg);
            desiredProjectionService.write(member, reader, face);
            var row = desiredMemberProjectionRepository.findByDeviceIdAndMemberId(reader.getId(), member.getId())
                    .orElseThrow();
            ids.add(new String[] {row.getDeviceUserId(), row.getReaderName()});
        }
        report("setup users=%d seconds=%.1f", USERS, (System.nanoTime() - setup) / 1e9);

        String users = list(ids);
        String whole = "{\"announcedTotal\":" + USERS + ",\"users\":" + users + "}";
        Measured first = measure(() -> assertThat(send(readerId, whole)).isEqualTo(200));
        report("first list users=%d ms=%d statements=%d readerLockMs=%d longestHoldMs=%d (snapshots inserted)",
                USERS, first.millis(), first.statements(), first.lockMillis(), first.longestHoldMillis());

        for (int round = 1; round <= ROUNDS; round++) {
            Measured before = measure(() -> {
                for (String[] id : ids) {
                    assertThat(send(readerId, user(id))).isEqualTo(200);
                }
                assertThat(send(readerId, whole)).isEqualTo(200);
            });
            report("scan before (per-user + list) round=%d ms=%d statements=%d readerLockMs=%d longestHoldMs=%d",
                    round, before.millis(), before.statements(), before.lockMillis(), before.longestHoldMillis());
            Measured after = measure(() -> assertThat(send(readerId, whole)).isEqualTo(200));
            report("scan after (list only) round=%d ms=%d statements=%d readerLockMs=%d longestHoldMs=%d",
                    round, after.millis(), after.statements(), after.lockMillis(), after.longestHoldMillis());
        }

        for (int round = 0; round < 3; round++) {
            Measured staff = measure(() -> assertThat(mockMvc.perform(post("/api/v1/devices/" + readerId + "/reconcile")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200));
            report("staff reconcile round=%d ms=%d statements=%d", round, staff.millis(), staff.statements());
        }
        assertThat(memberDeviceMappingRepository.findByDeviceId(reader.getId())).hasSize(USERS);
    }

    private record Measured(long millis, long statements, long lockMillis, long longestHoldMillis) {
    }

    private interface Step {
        void run() throws Exception;
    }

    /**
     * Wall time, statements the server sent to MySQL, and how long some transaction held a row lock
     * on {@code reader_revision} (the reader lock), sampled every 5 ms from another connection.
     * The sampler's own queries are not counted as statements.
     */
    private Measured measure(Step step) throws Exception {
        LockWatch watch = new LockWatch();
        Thread sampler = new Thread(watch);
        long before = questions();
        sampler.start();
        long start = System.nanoTime();
        try {
            step.run();
        } finally {
            watch.stop = true;
            sampler.join();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Measured(millis, questions() - before - 1 - watch.queries,
                watch.heldNanos / 1_000_000, watch.longestNanos / 1_000_000);
    }

    private static final class LockWatch implements Runnable {
        volatile boolean stop;
        long queries;
        long heldNanos;
        long longestNanos;

        @Override
        public void run() {
            try (var connection = rootConnection(); var statement = connection.createStatement()) {
                long last = System.nanoTime();
                long holdStart = -1;
                while (!stop) {
                    boolean held;
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM performance_schema.data_locks "
                            + "WHERE OBJECT_NAME = 'reader_revision' AND LOCK_TYPE = 'RECORD'")) {
                        rows.next();
                        held = rows.getInt(1) > 0;
                    }
                    queries++;
                    long now = System.nanoTime();
                    if (held) {
                        heldNanos += now - last;
                        if (holdStart < 0) {
                            holdStart = last;
                        }
                        longestNanos = Math.max(longestNanos, now - holdStart);
                    } else {
                        holdStart = -1;
                    }
                    last = now;
                    Thread.sleep(5);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static long questions() throws Exception {
        try (var connection = rootConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW GLOBAL STATUS LIKE 'Questions'")) {
            rows.next();
            return rows.getLong(2);
        }
    }

    private static String list(List<String[]> ids) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(user(ids.get(i)));
        }
        return out.append(']').toString();
    }

    private static String user(String[] user) {
        return "{\"deviceUserId\":\"" + user[0] + "\",\"name\":\"" + user[1] + "\",\"userStatus\":0,"
                + "\"authority\":\"Customer\",\"isNew\":true,\"deleted\":false}";
    }

    private int send(String devicePublicId, String payload) throws Exception {
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId, UUID.randomUUID(), Scans.stamp(payload));
        return mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andReturn().getResponse().getStatus();
    }

    private static void report(String format, Object... args) {
        System.out.println("ROSTER-LOAD " + String.format(format, args));
    }
}
