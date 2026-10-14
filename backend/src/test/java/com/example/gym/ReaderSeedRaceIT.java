package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.ReaderReconcileService;
import com.example.gym.device.ReaderRevisions;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.LocalDate;
import java.util.Optional;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A reader-wide seed racing a deactivation or a membership freeze. Whichever commits last,
 * the member must not stay enabled on the newly seeded reader. Each test holds the new reader's
 * lock in a controlling transaction so the interleaving is fixed rather than left to timing.
 */
class ReaderSeedRaceIT extends AbstractIntegrationTest {

    @Autowired
    private ReaderReconcileService reconcileService;

    @Autowired
    private ReaderRevisions readerRevisions;

    @Autowired
    private TransactionTemplate transactions;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Seed Race Gym", "seed-race");
        createUser(tenant.getId(), "seed-admin", "seed-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("seed-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** The seed has written the new reader and not committed when the deactivation runs. */
    @Test
    void aDeactivationDuringAnUncommittedSeedDisablesTheMemberOnTheSeededReader() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "RACE-D1", "3101"));
        Long exit = deviceId(createReader("Exit"));
        Future<Integer> deactivation = transactions.execute(status -> {
            assertThat(reconcileService.reconcile(deviceRepository.findById(exit).orElseThrow())).isEqualTo(1);
            int waits = lockWaits();
            Future<Integer> started = pool.submit(() -> deactivateStatus(ria));
            awaitDoneOrLockWaits(started, waits + 1);
            return started;
        });

        assertThat(deactivation.get(30, TimeUnit.SECONDS)).isEqualTo(204);
        assertNotEnabled(exit, ria);
    }

    /** The deactivation commits while the seed, whose snapshot predates it, waits for the reader. */
    @Test
    void aDeactivationCommittedWhileASeedWaitsForTheReaderIsNotUndoneByTheSeed() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "RACE-D2", "3102"));
        String exitId = createReader("Exit");
        Long exit = deviceId(exitId);
        Futures racing = holdingReader(exit, () -> reconcileStatus(exitId), () -> deactivateStatus(ria));

        assertThat(racing.seed().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(racing.change().get(30, TimeUnit.SECONDS)).isEqualTo(204);
        assertNotEnabled(exit, ria);
    }

    /** The deactivation is queued on the reader first; the seed's snapshot is taken before it commits. */
    @Test
    void aSeedQueuedBehindADeactivationDoesNotWriteTheMemberEnabled() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "RACE-D3", "3103"));
        String exitId = createReader("Exit");
        Long exit = deviceId(exitId);
        Futures racing = holdingReaderChangeFirst(exit, () -> deactivateStatus(ria), () -> reconcileStatus(exitId));

        assertThat(racing.change().get(30, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(racing.seed().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertNotEnabled(exit, ria);
    }

    /** A membership freeze racing a seed: the frozen member is disabled on the seeded reader. */
    @Test
    void aFreezeCommittedWhileASeedWaitsForTheReaderLeavesTheMemberDisabled() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "RACE-F1", "3104"));
        String membership = paidMembership(ria);
        String exitId = createReader("Exit");
        Long exit = deviceId(exitId);
        Futures racing = holdingReader(exit, () -> reconcileStatus(exitId), () -> freezeStatus(membership));

        assertThat(racing.seed().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(racing.change().get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertNotEnabled(exit, ria);
    }

    /** A freeze during an uncommitted seed. */
    @Test
    void aFreezeDuringAnUncommittedSeedDisablesTheMemberOnTheSeededReader() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "RACE-F2", "3105"));
        String membership = paidMembership(ria);
        Long exit = deviceId(createReader("Exit"));
        Future<Integer> freeze = transactions.execute(status -> {
            assertThat(reconcileService.reconcile(deviceRepository.findById(exit).orElseThrow())).isEqualTo(1);
            int waits = lockWaits();
            Future<Integer> started = pool.submit(() -> freezeStatus(membership));
            awaitDoneOrLockWaits(started, waits + 1);
            return started;
        });

        assertThat(freeze.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertNotEnabled(exit, ria);
    }

    /** A rename racing a seed still reaches the seeded reader, and neither side deadlocks. */
    @Test
    void aRenameDuringAnUncommittedSeedReachesTheSeededReader() throws Exception {
        createReader("Entrance");
        Member ria = member(create("Ria", "RACE-R1", "3106"));
        Long exit = deviceId(createReader("Exit"));
        Future<Integer> rename = transactions.execute(status -> {
            assertThat(reconcileService.reconcile(deviceRepository.findById(exit).orElseThrow())).isEqualTo(1);
            int waits = lockWaits();
            Future<Integer> started = pool.submit(() -> renameStatus(ria, "Moved"));
            awaitDoneOrLockWaits(started, waits + 1);
            return started;
        });

        assertThat(rename.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(projection(exit, ria).orElseThrow().getReaderName()).isEqualTo("Moved Shah");
    }

    /** Reactivation still seeds the reader, enabled, after the locking change. */
    @Test
    void aMemberDeactivatedAndReactivatedIsEnabledOnEveryReader() throws Exception {
        Long entrance = deviceId(createReader("Entrance"));
        Member ria = member(create("Ria", "RACE-A1", "3107"));
        assertThat(deactivateStatus(ria)).isEqualTo(204);
        Long exit = deviceId(createReader("Exit"));

        mockMvc.perform(post("/api/v1/members/" + ria.getPublicId() + "/reactivate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(projection(entrance, ria).orElseThrow().getUserStatus()).isZero();
        assertThat(projection(exit, ria).orElseThrow().getUserStatus()).isZero();
    }

    private record Futures(Future<Integer> seed, Future<Integer> change) {
    }

    /**
     * Holds the reader, starts the seed so it waits on that reader with its snapshot already taken,
     * then starts the change and lets it either finish or wait, and releases the reader.
     */
    private Futures holdingReader(Long device, java.util.concurrent.Callable<Integer> seed,
                                  java.util.concurrent.Callable<Integer> change) {
        return transactions.execute(status -> {
            readerRevisions.lock(tenant.getId(), device);
            int before = lockWaits();
            Future<Integer> seeding = pool.submit(seed);
            awaitDoneOrLockWaits(seeding, before + 1);
            int waits = lockWaits();
            Future<Integer> changing = pool.submit(change);
            awaitDoneOrLockWaits(changing, waits + 1);
            return new Futures(seeding, changing);
        });
    }

    /** As {@link #holdingReader}, with the change queued on the reader before the seed starts. */
    private Futures holdingReaderChangeFirst(Long device, java.util.concurrent.Callable<Integer> change,
                                             java.util.concurrent.Callable<Integer> seed) {
        return transactions.execute(status -> {
            readerRevisions.lock(tenant.getId(), device);
            int before = lockWaits();
            Future<Integer> changing = pool.submit(change);
            awaitDoneOrLockWaits(changing, before + 1);
            int waits = lockWaits();
            Future<Integer> seeding = pool.submit(seed);
            awaitDoneOrLockWaits(seeding, waits + 1);
            return new Futures(seeding, changing);
        });
    }

    private void assertNotEnabled(Long device, Member member) {
        Optional<DesiredMemberProjection> row = projection(device, member);
        row.ifPresent(found -> assertThat(found.getUserStatus())
                .as("member must not stay enabled on the seeded reader")
                .isEqualTo(1));
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

    /** Returns once the call has finished or the database shows at least that many lock waits. */
    private static void awaitDoneOrLockWaits(Future<?> call, int waits) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (var connection = rootConnection(); var statement = connection.createStatement()) {
            while (!call.isDone()) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                    rows.next();
                    if (rows.getInt(1) >= waits) {
                        return;
                    }
                }
                assertThat(System.nanoTime()).as("call finished or waiting on a lock").isLessThan(deadline);
                Thread.sleep(20);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
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

    private int freezeStatus(String membership) throws Exception {
        return postJson("/api/v1/memberships/" + membership + "/freeze", null).andReturn().getResponse().getStatus();
    }

    private int renameStatus(Member member, String firstName) throws Exception {
        return mockMvc.perform(put("/api/v1/members/" + member.getPublicId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + firstName + "\",\"lastName\":\"Shah\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int reconcileStatus(String devicePublicId) throws Exception {
        return mockMvc.perform(post("/api/v1/devices/" + devicePublicId + "/reconcile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
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

    private Optional<DesiredMemberProjection> projection(Long deviceId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, member.getId());
    }

    private String createReader(String name) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.41\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(body == null ? request : request.content(body));
    }
}
