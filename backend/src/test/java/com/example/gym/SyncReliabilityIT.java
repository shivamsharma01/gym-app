package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.AttendanceEventWriter;
import com.example.gym.device.DeviceSyncService;
import com.example.gym.device.DeviceUserChangeService;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.AccessDirection;
import com.example.gym.device.domain.AccessResult;
import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.AttendanceSyncCursor;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.face.FaceStorageService;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Server-side delivery guarantees: commands wait for an offline gateway instead of being given up,
 * reconnecting runs the catch-up step, a gateway message that failed is processed when resent,
 * photo files follow the database transaction, and a concurrent duplicate door event does not
 * break the surrounding transaction.
 */
class SyncReliabilityIT extends AbstractIntegrationTest {

    @Autowired
    private DeviceSyncService deviceSyncService;
    @Autowired
    private GatewayMessageService gatewayMessageService;
    @MockitoSpyBean
    private DeviceUserChangeService deviceUserChangeService;
    @Autowired
    private FaceStorageService storage;
    @Autowired
    private AttendanceEventWriter attendanceWriter;
    @Autowired
    private TransactionTemplate transactions;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String deviceId;
    private Long device;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Reliable Gym", "reliable-gym");
        createUser(tenant.getId(), "rel-admin", "rel-admin@rel.local", "GYM_ADMIN");
        token = tokenFor("rel-admin");
        gatewayId = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN-rel\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        deviceId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.40\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        device = deviceRepository.findByPublicId(deviceId).orElseThrow().getId();
    }

    @Test
    void commandsWaitForAnOfflineGatewayWithoutUsingUpAttemptsAndReconnectReleasesThem() throws Exception {
        postJson("/api/v1/members", "{\"firstName\":\"Om\",\"lastName\":\"Das\",\"memberCode\":\"9001\",\"serialNumber\":\"9001\"}")
                .andExpect(status().isCreated());

        // Far more delivery rounds than the 6 allowed attempts.
        for (int i = 0; i < 10; i++) {
            makeAllDue();
            deviceSyncService.dispatchDue();
        }
        List<DeviceSyncCommand> commands = deviceSyncCommandRepository.findAll();
        // The dispatcher leaves commands for an offline gateway untouched.
        assertThat(commands).isNotEmpty().allSatisfy(c -> {
            assertThat(c.getState()).isEqualTo(SyncCommandState.PENDING);
            assertThat(c.getAttemptCount()).isZero();
            assertThat(c.getDispatchedAt()).isNull();
        });

        // Still due, so the first dispatch after the gateway connects sends them.
        assertThat(deviceSyncCommandRepository.findAll())
                .allMatch(c -> !c.getNextAttemptAt().isAfter(Instant.now()));
    }

    @Test
    void registeringAGatewayRunsTheCatchUpStep() {
        gatewayMessageService.process(envelope("REGISTER_GATEWAY", UUID.randomUUID().toString(),
                "{\"agentVersion\":\"1\"}"));
        assertThat(deviceSyncCommandRepository.findAll())
                .anyMatch(c -> c.getType() == SyncCommandType.RECONCILE_DEVICE && c.getDeviceId().equals(device));
    }

    @Test
    void aMessageThatFailedIsProcessedWhenTheGatewayResendsIt() {
        doThrow(new IllegalStateException("database hiccup"))
                .doCallRealMethod()
                .when(deviceUserChangeService).apply(any(), any());
        String messageId = UUID.randomUUID().toString();
        String message = envelope("DEVICE_USER_CHANGED", messageId, """
                {"deviceUserId":"9100","name":"Nisha Rao","frozen":false,"deviceChangedAt":"%s","isNew":true,
                 "profileChanged":true,"nameChanged":true,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":false,"faceRemoved":false}
                """.formatted(Instant.now()));

        assertThat(gatewayMessageService.process(message).orElseThrow()).contains("ERROR");
        assertThat(gatewayMessageDedupeRepository.existsById(messageId)).isFalse();
        assertThat(memberRepository.findByTenantIdAndSerialNumber(tenant.getId(), "9100")).isEmpty();

        // Resent (the gateway keeps it until ACK): processed this time, then deduplicated.
        assertThat(gatewayMessageService.process(message).orElseThrow()).contains("ACK");
        assertThat(memberRepository.findByTenantIdAndSerialNumber(tenant.getId(), "9100")).isPresent();
        gatewayMessageService.process(message);
        verify(deviceUserChangeService, times(2)).apply(any(), any());
        doCallRealMethod().when(deviceUserChangeService).apply(any(), any());
    }

    @Test
    void photoFilesFollowTheDatabaseTransaction() {
        String old = FaceStorageService.memberKey(tenant.getId(), 1L, 1);
        String replacement = FaceStorageService.memberKey(tenant.getId(), 1L, 2);
        storage.write(old, new byte[] {1, 2, 3});

        // Rolled back: the database still points at the old file, so it stays; the new one is removed.
        transactions.executeWithoutResult(status -> {
            storage.writeInTransaction(replacement, new byte[] {4, 5, 6});
            storage.deleteAfterCommit(old);
            status.setRollbackOnly();
        });
        assertThat(storage.exists(old)).isTrue();
        assertThat(storage.exists(replacement)).isFalse();

        // Committed: the old file goes, the new one stays.
        transactions.executeWithoutResult(status -> {
            storage.writeInTransaction(replacement, new byte[] {4, 5, 6});
            storage.deleteAfterCommit(old);
        });
        assertThat(storage.exists(old)).isFalse();
        assertThat(storage.exists(replacement)).isTrue();
    }

    @Test
    void aConcurrentDuplicateDoorEventDoesNotBreakTheSurroundingTransaction() {
        Instant at = Instant.now();
        attendanceWriter.insert(event(at));

        transactions.executeWithoutResult(status -> {
            assertThatThrownBy(() -> attendanceWriter.insert(event(at)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            AttendanceSyncCursor cursor = new AttendanceSyncCursor(tenant.getId(), device);
            cursor.setLastEventAt(at);
            attendanceSyncCursorRepository.save(cursor);
        });
        assertThat(attendanceSyncCursorRepository.findByDeviceId(device)).isPresent();
        assertThat(attendanceEventRepository.count()).isEqualTo(1);
    }

    @Test
    void aReaderChangeTheGatewayAlreadyCopiedIsNotSentToItsOtherReaderAgain() throws Exception {
        String exitId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Exit\",\"role\":\"EXIT\",\"host\":\"10.0.0.41\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        Long exit = deviceRepository.findByPublicId(exitId).orElseThrow().getId();
        deviceSyncCommandRepository.deleteAll();

        gatewayMessageService.process(envelope("DEVICE_USER_CHANGED", UUID.randomUUID().toString(), """
                {"deviceUserId":"9300","name":"Asha Pal","frozen":false,"deviceChangedAt":"%s","isNew":true,
                 "profileChanged":true,"nameChanged":true,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":false,"faceRemoved":false,"siblingsUpdated":true,"siblingDeviceIds":["%s"]}
                """.formatted(Instant.now(), exitId)));
        gatewayMessageService.process(envelope("DEVICE_USER_CHANGED", UUID.randomUUID().toString(), """
                {"deviceUserId":"9300","name":"Asha Pal Singh","frozen":false,"deviceChangedAt":"%s","isNew":false,
                 "profileChanged":true,"nameChanged":true,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":false,"faceRemoved":false,"siblingsUpdated":true,"siblingDeviceIds":["%s"]}
                """.formatted(Instant.now(), exitId)));

        assertThat(memberRepository.findByTenantIdAndSerialNumber(tenant.getId(), "9300")).isPresent();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(exit, "9300")).isPresent();
        assertThat(userCommands(exit)).isEmpty();

        // An older gateway without the flag: the other reader still gets the new user.
        gatewayMessageService.process(envelope("DEVICE_USER_CHANGED", UUID.randomUUID().toString(), """
                {"deviceUserId":"9301","name":"Ravi Jain","frozen":false,"deviceChangedAt":"%s","isNew":true,
                 "profileChanged":true,"nameChanged":true,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":false,"faceRemoved":false}
                """.formatted(Instant.now())));
        assertThat(userCommands(exit)).extracting(DeviceSyncCommand::getType).contains(SyncCommandType.CREATE_USER);
        assertThat(userCommands(device)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private List<DeviceSyncCommand> userCommands(Long deviceId) {
        return deviceSyncCommandRepository.findAll().stream()
                .filter(c -> c.getDeviceId().equals(deviceId))
                .filter(c -> c.getType() == SyncCommandType.CREATE_USER || c.getType() == SyncCommandType.UPDATE_USER)
                .toList();
    }

    private AttendanceEvent event(Instant at) {
        return new AttendanceEvent(tenant.getId(), device, null, "9200", at, AccessDirection.IN, "FACE",
                AccessResult.GRANTED, 77L, "rec:77");
    }

    @Test
    void commandsClaimedByOneDispatcherAreSkippedByAConcurrentOne() throws Exception {
        postJson("/api/v1/members", "{\"firstName\":\"Ira\",\"lastName\":\"Sen\",\"memberCode\":\"9002\",\"serialNumber\":\"9002\"}")
                .andExpect(status().isCreated());
        makeAllDue();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = pool.submit(() -> transactions.execute(s -> {
                int n = claimDue().size();
                claimed.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return n;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

            Integer second = transactions.execute(s -> claimDue().size());
            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isPositive();
            assertThat(second).isZero();
            Integer afterRelease = transactions.execute(s -> claimDue().size());
            assertThat(afterRelease).isPositive();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private List<DeviceSyncCommand> claimDue() {
        return deviceSyncCommandRepository.claimDue(List.of(device),
                List.of(SyncCommandState.PENDING, SyncCommandState.RETRYING), Instant.now(), PageRequest.of(0, 50));
    }

    private void makeAllDue() {
        for (DeviceSyncCommand c : deviceSyncCommandRepository.findAll()) {
            c.setNextAttemptAt(Instant.now().minusSeconds(1));
            deviceSyncCommandRepository.save(c);
        }
    }

    private String envelope(String type, String messageId, String payload) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(messageId, Instant.now(), gatewayId, deviceId, type, UUID.randomUUID(), payload);
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
