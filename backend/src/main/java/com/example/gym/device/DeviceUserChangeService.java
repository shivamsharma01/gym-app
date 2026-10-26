package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * A reader report is an observation. It is stored for review. It does not change the member,
 * create a member, or write another reader. It is processed under the reader's lock, in the
 * reader's scan order: an observation older than what the server already has changes nothing.
 * Everything after the lock is read from the latest commits, so a seed or decision committed while
 * this observation waited for the lock is seen.
 * <p>
 * A complete list of any length is saved in short batches. Each batch commits and releases the
 * reader lock, so a list of hundreds of people does not make the next update wait out MySQL's
 * lock timeout. Seeding and claiming run after the list is recorded as saved, so a slow seed does
 * not withhold the ACK that opens the gateway's fence.
 */
@Service
public class DeviceUserChangeService {

    private static final Logger log = LoggerFactory.getLogger(DeviceUserChangeService.class);

    /** People stored per commit. Short enough that the reader lock is never held for the lock wait. */
    public static final int ROSTER_BATCH = 25;

    private final PendingEnrollmentService pendingEnrollments;
    private final ObservationOrder order;
    private final ReaderReconcileService reconcile;
    private final SeenMemberClaim claims;
    private final PunchMemberResolver punches;
    private final TransactionTemplate chunks;
    private final ThreadLocal<Runnable> followUp = new ThreadLocal<>();

    public DeviceUserChangeService(PendingEnrollmentService pendingEnrollments,
                                   ObservationOrder order,
                                   ReaderReconcileService reconcile,
                                   SeenMemberClaim claims,
                                   PunchMemberResolver punches,
                                   PlatformTransactionManager transactions) {
        this.pendingEnrollments = pendingEnrollments;
        this.order = order;
        this.reconcile = reconcile;
        this.claims = claims;
        this.punches = punches;
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.chunks = template;
    }

    /** @throws ObservationOrder.Unordered when the observation has no usable scan stamp */
    public void apply(Device device, JsonNode payload) {
        followUp.remove();
        JsonNode users = payload == null ? null : payload.get("users");
        int announced = payload == null ? -1 : payload.path("announcedTotal").asInt(-1);
        if (users != null && users.isArray() && users.size() > ROSTER_BATCH && announced == users.size()) {
            applyRoster(device, payload, users);
            return;
        }
        chunks.executeWithoutResult(status -> {
            ObservationOrder.Scan scan = order.admit(device, payload);
            if (!scan.stale()) {
                pendingEnrollments.observe(device, payload, scan);
            }
        });
    }

    /**
     * Work deferred until after the list message is recorded as saved. Safe to call when nothing
     * was deferred. A failure here must not unsave the list.
     */
    public void runFollowUp() {
        Runnable next = followUp.get();
        followUp.remove();
        if (next != null) {
            next.run();
        }
    }

    private void applyRoster(Device device, JsonNode payload, JsonNode users) {
        long listStarted = System.nanoTime();
        long admitStarted = listStarted;
        ObservationOrder.Scan scan = chunks.execute(status -> {
            ObservationOrder.Scan admitted = order.admit(device, payload);
            if (!admitted.stale()) {
                pendingEnrollments.markRoster(device, users.isEmpty());
            }
            return admitted;
        });
        FlowLog.info("device", "reader={} list admit done people={} stale={} ms={}",
                device.getPublicId(), users.size(), scan.stale(), msSince(admitStarted));
        if (scan.stale()) {
            return;
        }
        List<JsonNode> batch = new ArrayList<>(ROSTER_BATCH);
        Set<String> present = new HashSet<>();
        int batchIndex = 0;
        for (JsonNode user : users) {
            String deviceUserId = text(user, "deviceUserId");
            if (deviceUserId != null) {
                present.add(deviceUserId);
            }
            batch.add(user);
            if (batch.size() == ROSTER_BATCH) {
                batchIndex++;
                runRosterBatch(device, scan, List.copyOf(batch), batchIndex);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            batchIndex++;
            runRosterBatch(device, scan, List.copyOf(batch), batchIndex);
        }
        long finishStarted = System.nanoTime();
        chunks.executeWithoutResult(status -> pendingEnrollments.finishRoster(device, scan, present));
        FlowLog.info("device",
                "reader={} saved a list of {} people in {} batches of {} finishMs={} totalMs={}",
                device.getPublicId(), users.size(), batchIndex, ROSTER_BATCH,
                msSince(finishStarted), msSince(listStarted));
        Device reader = device;
        Set<String> credited = Set.copyOf(present);
        followUp.set(() -> {
            seedAndClaim(reader);
            creditEarlierPunches(reader, credited);
        });
    }

    private void runRosterBatch(Device device, ObservationOrder.Scan scan, List<JsonNode> saving, int batchIndex) {
        long started = System.nanoTime();
        chunks.executeWithoutResult(status -> pendingEnrollments.observeRosterBatch(device, saving, scan));
        FlowLog.info("device", "reader={} list batch {} size={} ms={}",
                device.getPublicId(), batchIndex, saving.size(), msSince(started));
    }

    /**
     * Idempotent seed/claim after a list was already recorded as saved. Used when the gateway
     * replays a messageId whose follow-up never ran (ACK succeeded, seed failed or was skipped).
     */
    public void ensureListFollowUp(Device device) {
        seedAndClaim(device);
    }

    private void seedAndClaim(Device device) {
        Long afterMember = null;
        while (true) {
            final Long from = afterMember;
            Long next = chunks.execute(status -> reconcile.seedAfter(device, from, ROSTER_BATCH));
            if (next == null) {
                break;
            }
            afterMember = next;
        }
        List<Long> undecided = chunks.execute(status -> claims.undecidedIds(device));
        for (int i = 0; i < undecided.size(); i += ROSTER_BATCH) {
            List<Long> page = undecided.subList(i, Math.min(i + ROSTER_BATCH, undecided.size()));
            chunks.executeWithoutResult(status -> claims.claimThese(device, page));
        }
        log.debug("{}: finished seeding and claiming after a saved list", device.getPublicId());
    }

    /** After the list is recorded as saved — never fails the list when linking deadlocks. */
    private void creditEarlierPunches(Device device, Set<String> deviceUserIds) {
        for (String deviceUserId : deviceUserIds) {
            punches.creditEarlierPunches(device, deviceUserId);
        }
    }

    private static long msSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
