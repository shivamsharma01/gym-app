package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * lock timeout.
 */
@Service
public class DeviceUserChangeService {

    /** People stored per commit. Short enough that the reader lock is never held for the lock wait. */
    public static final int ROSTER_BATCH = 25;

    private final PendingEnrollmentService pendingEnrollments;
    private final ObservationOrder order;
    private final ReaderReconcileService reconcile;
    private final SeenMemberClaim claims;
    private final TransactionTemplate chunks;

    public DeviceUserChangeService(PendingEnrollmentService pendingEnrollments,
                                   ObservationOrder order,
                                   ReaderReconcileService reconcile,
                                   SeenMemberClaim claims,
                                   PlatformTransactionManager transactions) {
        this.pendingEnrollments = pendingEnrollments;
        this.order = order;
        this.reconcile = reconcile;
        this.claims = claims;
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.chunks = template;
    }

    /** @throws ObservationOrder.Unordered when the observation has no usable scan stamp */
    public void apply(Device device, JsonNode payload) {
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

    private void applyRoster(Device device, JsonNode payload, JsonNode users) {
        ObservationOrder.Scan scan = chunks.execute(status -> {
            ObservationOrder.Scan admitted = order.admit(device, payload);
            if (!admitted.stale()) {
                pendingEnrollments.markRoster(device, users.isEmpty());
            }
            return admitted;
        });
        if (scan.stale()) {
            return;
        }
        List<JsonNode> batch = new ArrayList<>(ROSTER_BATCH);
        Set<String> present = new HashSet<>();
        for (JsonNode user : users) {
            String deviceUserId = text(user, "deviceUserId");
            if (deviceUserId != null) {
                present.add(deviceUserId);
            }
            batch.add(user);
            if (batch.size() == ROSTER_BATCH) {
                List<JsonNode> saving = List.copyOf(batch);
                chunks.executeWithoutResult(status -> pendingEnrollments.observeRosterBatch(device, saving, scan));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            List<JsonNode> saving = List.copyOf(batch);
            chunks.executeWithoutResult(status -> pendingEnrollments.observeRosterBatch(device, saving, scan));
        }
        chunks.executeWithoutResult(status -> pendingEnrollments.finishRoster(device, scan, present));
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
        FlowLog.info("device", "reader={} saved a list of {} people in batches of {}",
                device.getPublicId(), users.size(), ROSTER_BATCH);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
