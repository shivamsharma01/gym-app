package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReaderBaseline;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Stores a reader-created person as one pending enrollment. It does not create a member and it does
 * not allocate or change the device user id.
 */
@Service
public class PendingEnrollmentService {

    private final DeviceObservedUserRepository observedUsers;
    private final PendingEnrollmentRepository enrollments;
    private final MemberDeviceMappingRepository mappings;
    private final DesiredMemberProjectionRepository desiredMembers;
    private final ReaderReviewService reviews;
    private final DeviceRepository devices;
    private final BootstrapReportService bootstrap;
    private final PunchMemberResolver punches;
    private final SeenMemberClaim claims;
    private final DesiredProjectionService desired;

    public PendingEnrollmentService(DeviceObservedUserRepository observedUsers,
                                    PendingEnrollmentRepository enrollments,
                                    MemberDeviceMappingRepository mappings,
                                    DesiredMemberProjectionRepository desiredMembers,
                                    ReaderReviewService reviews,
                                    DeviceRepository devices,
                                    BootstrapReportService bootstrap,
                                    PunchMemberResolver punches,
                                    SeenMemberClaim claims,
                                    DesiredProjectionService desired) {
        this.observedUsers = observedUsers;
        this.enrollments = enrollments;
        this.mappings = mappings;
        this.desiredMembers = desiredMembers;
        this.reviews = reviews;
        this.devices = devices;
        this.bootstrap = bootstrap;
        this.punches = punches;
        this.claims = claims;
        this.desired = desired;
    }

    /**
     * One admitted observation of {@code device}, with the reader locked. A user this scan shows
     * older than the server already knows them is skipped.
     */
    @Transactional
    public void observe(Device device, JsonNode payload, ObservationOrder.Scan scan) {
        if (device == null || payload == null) {
            return;
        }
        JsonNode users = payload.get("users");
        if (users != null && users.isArray()) {
            observeSet(device, payload, users, scan);
        } else {
            observeOne(device, payload, scan, true);
        }
        claims.claimQuiet(device);
    }

    /** Records whether this complete list was empty. The device row is reloaded so a detached reader is not overwritten. */
    @Transactional
    public void markRoster(Device device, boolean trustedEmpty) {
        Device current = devices.findById(device.getId()).orElse(device);
        current.setRosterTrustedEmpty(trustedEmpty);
        devices.save(current);
    }

    /**
     * One slice of a complete list, under the reader lock. The lock ends when this batch commits,
     * so the next slice, a single-person update, or a member write can proceed.
     */
    @Transactional
    public void observeRosterBatch(Device device, List<JsonNode> users, ObservationOrder.Scan scan) {
        bootstrap.lockForRoster(device);
        Known known = known(device, scan);
        for (JsonNode user : users) {
            // Punch credit runs after the list is markProcessed — linking inside the batch deadlocks
            // with attendance ingest and rolls back the whole list (gateway "0 of 1" fence).
            if (!observeUnallocated(device, user, scan, known, false)) {
                observeOne(device, user, scan, false);
            }
        }
    }

    /**
     * After every slice of a complete list is stored: people the list no longer shows are closed,
     * and a mapped person missing from it is recorded absent.
     */
    @Transactional
    public void finishRoster(Device device, ObservationOrder.Scan scan, Set<String> present) {
        bootstrap.lockForRoster(device);
        Known known = known(device, scan);
        closeLeft(device, scan, known, present);
        for (DesiredMemberProjection desired : desiredMembers.findByDeviceId(device.getId())) {
            if (desired.isPresentOnReader() && !present.contains(desired.getDeviceUserId())
                    && wasOnReader(device, desired.getDeviceUserId())
                    && !scan.staleFor(desired.getDeviceUserId())) {
                reviews.recordAbsence(device, desired.getDeviceUserId());
            }
        }
    }

    /**
     * A short, empty, or mismatched list is not a disappearance and does not change the member.
     * A trusted list records a mapped id that is missing from it, when the reader had that id
     * before and has not verified it since this scan, and then seeds the active members this reader
     * does not have yet.
     */
    private void observeSet(Device device, JsonNode payload, JsonNode users, ObservationOrder.Scan scan) {
        int announced = payload.path("announcedTotal").asInt(-1);
        if (announced != users.size()) {
            rememberEmpty(device, false);
            return;
        }
        bootstrap.lockForRoster(device);
        rememberEmpty(device, users.isEmpty());
        Known known = known(device, scan);
        Set<String> present = new HashSet<>();
        for (JsonNode user : users) {
            if (!observeUnallocated(device, user, scan, known, true)) {
                observeOne(device, user, scan, true);
            }
            String deviceUserId = text(user, "deviceUserId");
            if (deviceUserId != null) {
                present.add(deviceUserId);
            }
        }
        closeLeft(device, scan, known, present);
        for (DesiredMemberProjection desired : desiredMembers.findByDeviceId(device.getId())) {
            if (desired.isPresentOnReader() && !present.contains(desired.getDeviceUserId())
                    && wasOnReader(device, desired.getDeviceUserId())
                    && !scan.staleFor(desired.getDeviceUserId())) {
                reviews.recordAbsence(device, desired.getDeviceUserId());
            }
        }
        bootstrap.seedFromTrustedRoster(device);
    }

    /**
     * This reader's rows, read once for a whole list under the reader lock. Processing a list never
     * gives out an id, so an id not allocated here stays unallocated until the list is done, and only
     * that id's own snapshot and enrollment change while it is processed.
     *
     * @param unservedSeeds whether any seed the gateway was never sent exists, which an undecided
     *                      person may withdraw
     */
    private record Known(Set<String> allocated, Map<String, DeviceObservedUser> snapshots,
                         Map<String, DeviceReaderBaseline> baselines, Map<String, PendingEnrollment> enrollments,
                         boolean unservedSeeds) {
    }

    private Known known(Device device, ObservationOrder.Scan scan) {
        Set<String> allocated = new HashSet<>();
        for (MemberDeviceMapping mapping : mappings.findByDeviceId(device.getId())) {
            allocated.add(mapping.getDeviceUserId());
            allocated.add(mapping.getPendingDeviceUserId());
        }
        boolean unservedSeeds = false;
        for (DesiredMemberProjection desired : desiredMembers.findByDeviceId(device.getId())) {
            allocated.add(desired.getDeviceUserId());
            unservedSeeds |= desired.unservedSeed(scan.servedRevision());
        }
        Map<String, DeviceObservedUser> snapshots = new HashMap<>();
        for (DeviceObservedUser snapshot : observedUsers.findByDeviceId(device.getId())) {
            snapshots.put(snapshot.getDeviceUserId(), snapshot);
        }
        Map<String, PendingEnrollment> pending = new HashMap<>();
        for (PendingEnrollment enrollment : enrollments.findByDeviceId(device.getId())) {
            pending.put(enrollment.getDeviceUserId(), enrollment);
        }
        return new Known(allocated, snapshots, reviews.baselines(device.getId()), pending, unservedSeeds);
    }

    /**
     * {@link #observeOne} for a person at an id the server never gave out, from the rows read once.
     * Each step that only applies to an allocated id is a no-op here.
     *
     * @return false when the id is allocated and must take the full path
     */
    private boolean observeUnallocated(Device device, JsonNode payload, ObservationOrder.Scan scan, Known known,
                                       boolean creditPunches) {
        String deviceUserId = text(payload, "deviceUserId");
        if (deviceUserId == null || deviceUserId.isBlank() || known.allocated().contains(deviceUserId)) {
            return false;
        }
        if (scan.staleFor(deviceUserId, known.snapshots().get(deviceUserId), known.baselines().get(deviceUserId),
                known.enrollments().get(deviceUserId)) || bool(payload, "deleted")) {
            return true;
        }
        DeviceObservedUser snapshot = saveSnapshot(device, payload, deviceUserId, scan.stamp(), false,
                known.snapshots().get(deviceUserId), creditPunches);
        known.snapshots().put(deviceUserId, snapshot);
        PendingEnrollment enrollment = reopen(device, deviceUserId, known.enrollments().get(deviceUserId));
        known.enrollments().put(deviceUserId, enrollment);
        if (enrollment.getDecision() == null && known.unservedSeeds()) {
            bootstrap.withdrawUnservedSeeds(device, snapshot);
        }
        return true;
    }

    /**
     * A person nobody decided on whom a complete list no longer shows has left the reader, and no
     * longer holds back a member. A decision already made waits for the reader as before, and a later
     * look at the person than this list keeps them.
     */
    private void closeLeft(Device device, ObservationOrder.Scan scan, Known known, Set<String> present) {
        int closed = 0;
        for (PendingEnrollment enrollment : known.enrollments().values()) {
            String deviceUserId = enrollment.getDeviceUserId();
            if (enrollment.isResolved() || enrollment.getDecision() != null || present.contains(deviceUserId)
                    || scan.staleFor(deviceUserId, known.snapshots().get(deviceUserId),
                    known.baselines().get(deviceUserId), enrollment)) {
                continue;
            }
            enrollment.leftReader();
            enrollments.save(enrollment);
            closed++;
        }
        if (closed > 0) {
            FlowLog.info("device", "reader={} no longer shows {} undecided people", device.getPublicId(), closed);
        }
    }

    /**
     * Seen on an earlier list or verified by a read-back. An id published before the reader ever
     * showed or verified it cannot have disappeared from that reader.
     */
    private boolean wasOnReader(Device device, String deviceUserId) {
        return observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).isPresent()
                || reviews.verifiedOnReader(device.getId(), deviceUserId);
    }

    private void observeOne(Device device, JsonNode payload, ObservationOrder.Scan scan, boolean creditPunches) {
        String deviceUserId = text(payload, "deviceUserId");
        if (deviceUserId == null || deviceUserId.isBlank() || scan.staleFor(deviceUserId)) {
            return;
        }
        if (bool(payload, "deleted")) {
            if (serverAllocated(device.getId(), deviceUserId)) {
                reviews.recordAbsence(device, deviceUserId);
            }
            return;
        }
        withdrawCollidingSeed(device, deviceUserId, scan);
        DeviceObservedUser snapshot = saveSnapshot(device, payload, deviceUserId, scan.stamp(), false, creditPunches);
        // Mapped id: any field drift (including name+photo) is a CONFLICT for staff — never auto-suspend.
        // True strangers only enroll when the id is not allocated to a member.
        if (serverAllocated(device.getId(), deviceUserId)) {
            reviews.record(device, payload, deviceUserId);
            desired.republishFinishedAbsence(device, snapshot);
            return;
        }
        if (enroll(device, deviceUserId)) {
            bootstrap.withdrawUnservedSeeds(device, snapshot);
        }
    }

    /**
     * A seed the gateway was never sent cannot be what the reader shows at its id: the server
     * allocated the id before it saw that the reader already uses it. The seed is taken back, so the
     * person there is treated as the reader's own.
     */
    private void withdrawCollidingSeed(Device device, String deviceUserId, ObservationOrder.Scan scan) {
        retireFinishedAbsence(device, deviceUserId);
        desiredMembers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .filter(row -> row.unservedSeed(scan.servedRevision()))
                .filter(row -> reviews.baseline(device.getId(), deviceUserId).isEmpty())
                .ifPresent(row -> {
                    mappings.findByDeviceIdAndMemberId(device.getId(), row.getMemberId()).ifPresent(mappings::delete);
                    desiredMembers.delete(row);
                    desiredMembers.flush();
                    FlowLog.info("device", "seed withdrawn reader={} user={}: the reader already uses the id",
                            device.getPublicId(), deviceUserId);
                });
    }

    /**
     * A finished removal leaves a row with no member. Giving that id to a member adds a second row.
     * The reader list then cannot be saved, and every later write stays Waiting. Drop the finished
     * removal when a member row for the same id exists, so the save continues.
     */
    private void retireFinishedAbsence(Device device, String deviceUserId) {
        List<DesiredMemberProjection> rows = desiredMembers.findAllByDeviceIdAndDeviceUserId(
                device.getId(), deviceUserId);
        if (rows.size() < 2 || rows.stream().noneMatch(row -> row.getMemberId() != null)) {
            return;
        }
        int removed = 0;
        List<DesiredMemberProjection> left = new ArrayList<>();
        for (DesiredMemberProjection row : rows) {
            if (row.getMemberId() == null && row.isApplied() && !row.isPresentOnReader()) {
                desiredMembers.delete(row);
                removed++;
            } else {
                left.add(row);
            }
        }
        desiredMembers.flush();
        if (removed > 0) {
            FlowLog.info("device", "dropped {} finished removal row(s) reader={} user={} so the reader list can be saved",
                    removed, device.getPublicId(), deviceUserId);
        }
        if (left.size() > 1) {
            FlowLog.warn("device", "reader={} user={} still has {} desired rows: {}",
                    device.getPublicId(), deviceUserId, left.size(), desiredRows(left));
        }
    }

    private static String desiredRows(List<DesiredMemberProjection> rows) {
        StringBuilder text = new StringBuilder();
        for (DesiredMemberProjection row : rows) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(row.getId())
                    .append("/member=").append(row.getMemberId())
                    .append("/rev=").append(row.getRevision())
                    .append("/applied=").append(row.isApplied())
                    .append("/present=").append(row.isPresentOnReader());
        }
        return text.toString();
    }

    /**
     * The reader holds someone the server has not attached to a member at this id. An earlier
     * decision about the id was about someone else, so it is reopened.
     *
     * @return true when the person is undecided
     */
    private boolean enroll(Device device, String deviceUserId) {
        if (serverAllocated(device.getId(), deviceUserId)) {
            return false;
        }
        return reopen(device, deviceUserId,
                enrollments.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null))
                .getDecision() == null;
    }

    private PendingEnrollment reopen(Device device, String deviceUserId, PendingEnrollment existing) {
        PendingEnrollment enrollment = existing != null ? existing
                : new PendingEnrollment(device.getTenantId(), device.getId(), deviceUserId);
        if (enrollment.isResolved()) {
            enrollment.clearDecision();
        }
        enrollment.setObservedAt(Instant.now());
        return enrollments.save(enrollment);
    }

    /**
     * The gateway found someone at an id other than the member it expected, and wrote nothing.
     * Opens a CONFLICT for staff (Keep reader / Overwrite / Remove). Never auto-suspends: a device
     * edit that changes every field is still that mapped member until staff say otherwise.
     */
    @Transactional
    public void occupantChanged(Device device, DesiredMemberProjection desired, ExpectedOccupant known,
                                JsonNode payload) {
        String deviceUserId = desired.getDeviceUserId();
        saveSnapshot(device, payload, deviceUserId, ScanStamp.of(payload), true, true);
        if (known != null) {
            FlowLog.info("device", "occupant changed reader={} user={} expectedPresent={}",
                    device.getPublicId(), deviceUserId, known.present());
        }
        reviews.recordChange(device, desired, payload);
        desired.setHeld(true);
        desiredMembers.save(desired);
    }

    /** The gateway found no one at a member's id it expected to hold someone. */
    @Transactional
    public void occupantGone(Device device, DesiredMemberProjection desired) {
        reviews.recordAbsence(device, desired.getDeviceUserId());
        desired.setHeld(true);
        desiredMembers.save(desired);
    }

    /**
     * A staff decision about a reader person (link, create or reject) found someone else at the id,
     * or no one. The decision is undone and the enrollment reopens with what the reader shows now.
     */
    @Transactional
    public void decisionOvertaken(Device device, DesiredMemberProjection desired, JsonNode payload) {
        String deviceUserId = desired.getDeviceUserId();
        if (desired.getMemberId() != null) {
            mappings.findByDeviceIdAndMemberId(device.getId(), desired.getMemberId()).ifPresent(mappings::delete);
        }
        desiredMembers.delete(desired);
        desiredMembers.flush();
        if (payload != null) {
            saveSnapshot(device, payload, deviceUserId, ScanStamp.of(payload), true, true);
        }
        PendingEnrollment enrollment = enrollments.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new PendingEnrollment(device.getTenantId(), device.getId(), deviceUserId));
        enrollment.clearDecision();
        enrollment.noteVerificationError(payload == null
                ? "The reader no longer has this person"
                : "The reader record changed before the decision was applied");
        enrollment.setObservedAt(Instant.now());
        enrollments.save(enrollment);
    }

    @Transactional(readOnly = true)
    public DeviceObservedUser snapshot(Long deviceId, String deviceUserId) {
        return observedUsers.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId).orElse(null);
    }

    private void rememberEmpty(Device device, boolean trustedEmpty) {
        device.setRosterTrustedEmpty(trustedEmpty);
        devices.save(device);
    }

    /**
     * What the reader shows at the id, from a scan, or from the gateway's read at write time
     * ({@code readBack}). A read at write time is later than every scan up to {@code stamp}.
     */
    private DeviceObservedUser saveSnapshot(Device device, JsonNode payload, String deviceUserId, ScanStamp stamp,
                                            boolean readBack, boolean creditPunches) {
        return saveSnapshot(device, payload, deviceUserId, stamp, readBack,
                observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null),
                creditPunches);
    }

    private DeviceObservedUser saveSnapshot(Device device, JsonNode payload, String deviceUserId, ScanStamp stamp,
                                            boolean readBack, DeviceObservedUser existing, boolean creditPunches) {
        DeviceObservedUser snapshot = existing != null ? existing
                : new DeviceObservedUser(device.getTenantId(), device.getId(), deviceUserId);
        if (stamp != null) {
            snapshot.observedIn(stamp.epoch(), stamp.seq(), readBack);
        } else if (readBack) {
            snapshot.observedIn(null, null, true);
        }
        snapshot.setReaderName(cut(text(payload, "name"), 127));
        snapshot.setReaderNameEx(cut(text(payload, "nameEx"), 127));
        snapshot.setUserStatus(number(payload, "userStatus"));
        snapshot.setValidFrom(cut(text(payload, "validFrom"), 40));
        snapshot.setValidTo(cut(text(payload, "validTo"), 40));
        snapshot.setAuthority(cut(text(payload, "authority"), 32));
        snapshot.setDoorNum(optionalNumber(payload, "doorNum"));
        snapshot.setTimeSectionNum(optionalNumber(payload, "timeSectionNum"));
        snapshot.setFaceSha256(face(text(payload, "faceSha256")));
        snapshot.setObservedAt(Instant.now());
        DeviceObservedUser saved = observedUsers.save(snapshot);
        if (creditPunches) {
            punches.creditEarlierPunches(device, saved.getDeviceUserId());
        }
        return saved;
    }

    private boolean serverAllocated(Long deviceId, String deviceUserId) {
        if (mappings.existsByDeviceIdAndDeviceUserId(deviceId, deviceUserId)) {
            return true;
        }
        if (mappings.findFirstByDeviceIdAndPendingDeviceUserId(deviceId, deviceUserId).isPresent()) {
            return true;
        }
        Optional<DesiredMemberProjection> desired =
                desiredMembers.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId);
        return desired.isPresent();
    }

    private static String face(String value) {
        return value != null && value.length() == 64 ? value : null;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private static Integer optionalNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static boolean bool(JsonNode node, String field) {
        return node.has(field) && node.get(field).asBoolean();
    }
}
