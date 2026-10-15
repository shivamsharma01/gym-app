package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReaderBaseline;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceReaderBaselineRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.common.logging.FlowLog;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Decides whether a reader observation may change server state, from its scan stamp alone.
 * <ul>
 *   <li>No stamp, or a stamp from a journal this reader retired: refused, and not recorded as processed.</li>
 *   <li>A journal the reader has not seen: accepted from now on; the previous one is retired.</li>
 *   <li>A scan before the newest complete list processed: stale. That list is a later look at the whole reader.</li>
 *   <li>For one reader user, a scan before the stored snapshot's scan: stale.</li>
 *   <li>For one reader user, a scan no later than the one the gateway had taken when it verified that
 *       user's record or a decision about them: stale. The read-back is the later look.</li>
 * </ul>
 * A stale observation is acknowledged and changes nothing. Content is never compared to decide.
 */
@Component
public class ObservationOrder {

    private final ReaderRevisions revisions;
    private final DeviceObservedUserRepository observedUsers;
    private final DeviceReaderBaselineRepository baselines;
    private final PendingEnrollmentRepository enrollments;

    public ObservationOrder(ReaderRevisions revisions,
                            DeviceObservedUserRepository observedUsers,
                            DeviceReaderBaselineRepository baselines,
                            PendingEnrollmentRepository enrollments) {
        this.revisions = revisions;
        this.observedUsers = observedUsers;
        this.baselines = baselines;
        this.enrollments = enrollments;
    }

    /**
     * Locks the reader and places the observation in its scan order. Call in the transaction that
     * processes the observation.
     *
     * @throws Unordered when the observation has no usable stamp
     */
    public Scan admit(Device device, JsonNode payload) {
        ScanStamp stamp = ScanStamp.of(payload);
        if (stamp == null) {
            throw new Unordered("the observation carries no scan order");
        }
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        if (!accept(cursor, stamp)) {
            throw new Unordered("the observation comes from a gateway journal this reader no longer accepts");
        }
        boolean stale = stamp.before(cursor.getObservationEpoch(), cursor.getObservationListSeq());
        JsonNode users = payload.get("users");
        if (!stale && users != null && users.isArray()
                && payload.path("announcedTotal").asInt(-1) == users.size()) {
            cursor.listProcessed(stamp.seq());
        }
        if (stale) {
            FlowLog.info("device", "stale observation ignored reader={} scan={}/{}: a later list was processed",
                    device.getPublicId(), stamp.epoch(), stamp.seq());
        }
        return new Scan(device, stamp, stale, cursor.getServedRevision());
    }

    /**
     * Whether a stamp from this journal may be used for this reader. A journal the reader has not
     * seen retires the current one. Call with the reader locked.
     */
    static boolean accept(ReaderRevision cursor, ScanStamp stamp) {
        if (stamp.epoch().equals(cursor.getRetiredObservationEpoch())) {
            return false;
        }
        if (!stamp.epoch().equals(cursor.getObservationEpoch())) {
            cursor.adoptObservationEpoch(stamp.epoch());
        }
        return true;
    }

    /** One admitted observation. */
    public final class Scan {

        private final Device device;
        private final ScanStamp stamp;
        private final boolean stale;
        private final long servedRevision;

        private Scan(Device device, ScanStamp stamp, boolean stale, long servedRevision) {
            this.device = device;
            this.stamp = stamp;
            this.stale = stale;
            this.servedRevision = servedRevision;
        }

        public ScanStamp stamp() {
            return stamp;
        }

        /** The highest revision any pull of this reader has served, read under the reader lock. */
        public long servedRevision() {
            return servedRevision;
        }

        /** A later complete list of this reader was processed already. */
        public boolean stale() {
            return stale;
        }

        /**
         * True when what this scan shows for {@code deviceUserId} is older than what the server has:
         * a later snapshot, or a read-back the gateway verified after this scan.
         */
        public boolean staleFor(String deviceUserId) {
            if (stale) {
                return true;
            }
            return staleFor(deviceUserId,
                    observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null),
                    baselines.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null),
                    enrollments.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null));
        }

        /** As {@link #staleFor(String)}, from the user's rows already read under the reader lock. */
        public boolean staleFor(String deviceUserId, DeviceObservedUser snapshot, DeviceReaderBaseline baseline,
                                PendingEnrollment enrollment) {
            if (stale) {
                return true;
            }
            if (snapshot != null && (snapshot.isObservedByReadBack()
                    ? stamp.notAfter(snapshot.getObservedEpoch(), snapshot.getObservedSeq())
                    : stamp.before(snapshot.getObservedEpoch(), snapshot.getObservedSeq()))) {
                return ignored(deviceUserId, "a later scan of this user was processed");
            }
            if (baseline != null && stamp.notAfter(baseline.getVerifiedEpoch(), baseline.getVerifiedSeq())) {
                return ignored(deviceUserId, "the reader verified this user's record after this scan");
            }
            if (enrollment != null && enrollment.isResolved()
                    && stamp.notAfter(enrollment.getSettledEpoch(), enrollment.getSettledSeq())) {
                return ignored(deviceUserId, "the reader verified the decision about this person after this scan");
            }
            return false;
        }

        private boolean ignored(String deviceUserId, String why) {
            FlowLog.info("device", "stale observation ignored reader={} user={} scan={}/{}: {}",
                    device.getPublicId(), deviceUserId, stamp.epoch(), stamp.seq(), why);
            return true;
        }
    }

    /** An observation whose place in the reader's scan order is unknown. It is not processed. */
    public static final class Unordered extends RuntimeException {
        public Unordered(String message) {
            super(message);
        }
    }
}
