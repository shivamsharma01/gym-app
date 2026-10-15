package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import java.time.Instant;
import java.util.HashSet;
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

    public PendingEnrollmentService(DeviceObservedUserRepository observedUsers,
                                    PendingEnrollmentRepository enrollments,
                                    MemberDeviceMappingRepository mappings,
                                    DesiredMemberProjectionRepository desiredMembers,
                                    ReaderReviewService reviews,
                                    DeviceRepository devices,
                                    BootstrapReportService bootstrap) {
        this.observedUsers = observedUsers;
        this.enrollments = enrollments;
        this.mappings = mappings;
        this.desiredMembers = desiredMembers;
        this.reviews = reviews;
        this.devices = devices;
        this.bootstrap = bootstrap;
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
            return;
        }
        observeOne(device, payload, scan);
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
        Set<String> present = new HashSet<>();
        for (JsonNode user : users) {
            observeOne(device, user, scan);
            String deviceUserId = text(user, "deviceUserId");
            if (deviceUserId != null) {
                present.add(deviceUserId);
            }
        }
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
     * Seen on an earlier list or verified by a read-back. An id published before the reader ever
     * showed or verified it cannot have disappeared from that reader.
     */
    private boolean wasOnReader(Device device, String deviceUserId) {
        return observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).isPresent()
                || reviews.verifiedOnReader(device.getId(), deviceUserId);
    }

    private void observeOne(Device device, JsonNode payload, ObservationOrder.Scan scan) {
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
        DeviceObservedUser snapshot = saveSnapshot(device, payload, deviceUserId, scan.stamp(), false);
        if (serverAllocated(device.getId(), deviceUserId)
                && !reviews.releaseIfReplaced(device, deviceUserId, payload)) {
            reviews.record(device, payload, deviceUserId);
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
     * The reader holds someone the server has not attached to a member at this id. An earlier
     * decision about the id was about someone else, so it is reopened.
     *
     * @return true when the person is undecided
     */
    private boolean enroll(Device device, String deviceUserId) {
        if (serverAllocated(device.getId(), deviceUserId)) {
            return false;
        }
        PendingEnrollment enrollment = enrollments.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new PendingEnrollment(device.getTenantId(), device.getId(), deviceUserId));
        if (enrollment.isResolved()) {
            enrollment.clearDecision();
        }
        enrollment.setObservedAt(Instant.now());
        enrollments.save(enrollment);
        return enrollment.getDecision() == null;
    }

    /**
     * The gateway found someone at an id other than the member it expected, and wrote nothing.
     * Someone else entirely suspends that member here and makes the person an enrollment; a change
     * to the member's own record opens the member's review. The row is held either way until staff decide.
     */
    @Transactional
    public void occupantChanged(Device device, DesiredMemberProjection desired, ExpectedOccupant known,
                                JsonNode payload) {
        String deviceUserId = desired.getDeviceUserId();
        saveSnapshot(device, payload, deviceUserId, ScanStamp.of(payload), true);
        if (known != null && ReaderReviewService.replaced(known, desired, payload)) {
            reviews.suspend(device, desired, payload);
            enroll(device, deviceUserId);
            return;
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
            saveSnapshot(device, payload, deviceUserId, ScanStamp.of(payload), true);
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
                                            boolean readBack) {
        DeviceObservedUser snapshot = observedUsers.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new DeviceObservedUser(device.getTenantId(), device.getId(), deviceUserId));
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
        return observedUsers.save(snapshot);
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
