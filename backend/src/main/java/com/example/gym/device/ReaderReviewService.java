package com.example.gym.device;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceReaderBaseline;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.DeviceReviewSnapshot;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceReaderBaselineRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.DeviceReviewSnapshotRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Compares one reader's observation with the desired record and the last reconciled baseline.
 * A difference is one review item. A second reader joins that same item and keeps its own snapshot.
 * The member row is left as it is, and {@code emAuthority} is stored and not written back.
 */
@Service
public class ReaderReviewService {

    private static final ZoneId READER_ZONE = ZoneId.of("Asia/Kolkata");

    private final DesiredMemberProjectionRepository desiredMembers;
    private final DeviceReaderBaselineRepository baselines;
    private final DeviceReviewItemRepository reviews;
    private final DeviceReviewSnapshotRepository snapshots;
    private final PendingEnrollmentRepository enrollments;

    public ReaderReviewService(DesiredMemberProjectionRepository desiredMembers,
                               DeviceReaderBaselineRepository baselines,
                               DeviceReviewItemRepository reviews,
                               DeviceReviewSnapshotRepository snapshots,
                               PendingEnrollmentRepository enrollments) {
        this.desiredMembers = desiredMembers;
        this.baselines = baselines;
        this.reviews = reviews;
        this.snapshots = snapshots;
        this.enrollments = enrollments;
    }

    @Transactional
    public void record(Device device, JsonNode payload, String deviceUserId) {
        DesiredMemberProjection desired = desiredMembers
                .findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (desired == null || desired.getMemberId() == null || !desired.isPresentOnReader()) {
            return;
        }
        DeviceReaderBaseline baseline = baselines
                .findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (agrees(payload, held(desired))) {
            if (baseline != null && !baselineAgrees(baseline, desired)) {
                copyBaseline(baseline, desired);
                baselines.save(baseline);
            }
            return;
        }
        if (baseline != null && agrees(payload, held(baseline))) {
            return;
        }

        DeviceReviewItem item = conflictFor(device, deviceUserId, desired.getMemberId());
        boolean thisReader = item.getId() == null || device.getId().equals(item.getDeviceId());
        if (thisReader && item.isResolved()) {
            item.clearDecision();
        }
        if (thisReader) {
            describe(item, desired, baseline, payload);
        }
        item = reviews.save(item);
        rememberSnapshot(item, device, deviceUserId, payload);
    }

    private static void describe(DeviceReviewItem item, DesiredMemberProjection desired,
                                 DeviceReaderBaseline baseline, JsonNode payload) {
        if (baseline == null) {
            item.setBaselineName(desired.getReaderName());
            item.setBaselineNameEx(desired.getReaderNameEx());
            item.setBaselineAuthority(desired.getAuthority());
            item.setBaselineValidFrom(desired.getValidFrom());
            item.setBaselineValidTo(desired.getValidTo());
            item.setBaselineUserStatus(desired.getUserStatus());
            item.setBaselineFaceSha256(serverFace(desired));
        } else {
            item.setBaselineName(baseline.getReaderName());
            item.setBaselineNameEx(baseline.getReaderNameEx());
            item.setBaselineAuthority(baseline.getAuthority());
            item.setBaselineValidFrom(baseline.getValidFrom());
            item.setBaselineValidTo(baseline.getValidTo());
            item.setBaselineUserStatus(baseline.getUserStatus());
            item.setBaselineFaceSha256(baseline.getFaceSha256());
        }
        describeServer(item, desired);
        item.setReaderName(cut(text(payload, "name"), 127));
        item.setReaderNameEx(cut(text(payload, "nameEx"), 127));
        item.setReaderAuthority(cut(text(payload, "authority"), 32));
        item.setReaderValidFrom(cut(text(payload, "validFrom"), 40));
        item.setReaderValidTo(cut(text(payload, "validTo"), 40));
        item.setReaderUserStatus(status(payload));
        item.setReaderFaceSha256(face(payload));
        item.setObservedAt(Instant.now());
    }

    private static void describeServer(DeviceReviewItem item, DesiredMemberProjection desired) {
        item.setServerName(desired.getReaderName());
        item.setServerNameEx(desired.getReaderNameEx());
        item.setServerAuthority(desired.getAuthority());
        item.setServerValidFrom(desired.getValidFrom());
        item.setServerValidTo(desired.getValidTo());
        item.setServerUserStatus(desired.getUserStatus());
        item.setServerFaceSha256(serverFace(desired));
    }

    /** A read-back of this id was accepted on this reader at least once. */
    public boolean verifiedOnReader(Long deviceId, String deviceUserId) {
        return baselines.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId).isPresent();
    }

    /**
     * A mapped id missing from a trusted list. The member and every other reader stay as they are.
     * A repeated observation updates this same item.
     */
    @Transactional
    public void recordAbsence(Device device, String deviceUserId) {
        DesiredMemberProjection desired = desiredMembers
                .findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (desired == null || !desired.isPresentOnReader()) {
            return;
        }
        DeviceReviewItem item = reviews.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElseGet(() -> new DeviceReviewItem(
                        device.getTenantId(), device.getId(), desired.getMemberId(), deviceUserId));
        if (item.isResolved()) {
            item.clearDecision();
        }
        if (item.getId() == null || item.getBaselineName() == null) {
            item.setBaselineName(desired.getReaderName());
            item.setBaselineNameEx(desired.getReaderNameEx());
            item.setBaselineAuthority(desired.getAuthority());
            item.setBaselineUserStatus(desired.getUserStatus());
            item.setBaselineFaceSha256(serverFace(desired));
            describeServer(item, desired);
        }
        item.setReaderAbsent(true);
        item.setObservedAt(Instant.now());
        reviews.save(item);
    }

    /** The first reader opens the conflict. A later reader keeps a second snapshot on that same item. */
    private DeviceReviewItem conflictFor(Device device, String deviceUserId, Long memberId) {
        DeviceReviewItem onThisReader = reviews.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (onThisReader != null) {
            return onThisReader;
        }
        List<DeviceReviewItem> shared = reviews.findByMemberId(memberId);
        if (!shared.isEmpty()) {
            return shared.get(0);
        }
        return new DeviceReviewItem(device.getTenantId(), device.getId(), memberId, deviceUserId);
    }

    private void rememberSnapshot(DeviceReviewItem item, Device device, String deviceUserId, JsonNode payload) {
        DeviceReviewSnapshot snapshot = snapshots.findByReviewItemIdAndDeviceId(item.getId(), device.getId())
                .orElseGet(() -> new DeviceReviewSnapshot(
                        device.getTenantId(), item.getId(), device.getId(), deviceUserId));
        snapshot.setReaderName(cut(text(payload, "name"), 127));
        snapshot.setReaderNameEx(cut(text(payload, "nameEx"), 127));
        snapshot.setReaderAuthority(cut(text(payload, "authority"), 32));
        snapshot.setReaderValidFrom(cut(text(payload, "validFrom"), 40));
        snapshot.setReaderValidTo(cut(text(payload, "validTo"), 40));
        snapshot.setReaderUserStatus(status(payload));
        snapshot.setReaderFaceSha256(face(payload));
        snapshot.setObservedAt(Instant.now());
        snapshots.save(snapshot);
    }

    /**
     * The reader verified revision {@code revision} of {@code deviceUserId}. Every open staff decision
     * for that user on this reader published at or below it is closed: the decision's own revision,
     * or one a later change of the same user replaced before the gateway applied it. The decision
     * itself is left as recorded. Call inside the acknowledgement's reader lock.
     */
    @Transactional
    public void settle(Long deviceId, String deviceUserId, long revision) {
        reviews.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId)
                .filter(item -> awaiting(item.isResolved(), item.getDecisionRevision(), revision))
                .ifPresent(item -> {
                    item.resolve();
                    reviews.save(item);
                });
        enrollments.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId)
                .filter(enrollment -> awaiting(enrollment.isResolved(), enrollment.getDecisionRevision(), revision))
                .ifPresent(enrollment -> {
                    enrollment.resolve();
                    enrollments.save(enrollment);
                });
    }

    /**
     * Read-back of revision {@code revision} of {@code deviceUserId} did not match. Every open decision
     * that revision would have closed stays open and keeps the verification error. Commits on its own
     * so the error survives the rejected acknowledgement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void noteVerificationFailure(Long deviceId, String deviceUserId, long revision, String error) {
        String stored = cut(error, 255);
        reviews.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId)
                .filter(item -> awaiting(item.isResolved(), item.getDecisionRevision(), revision))
                .ifPresent(item -> {
                    item.noteVerificationError(stored);
                    reviews.save(item);
                });
        enrollments.findByDeviceIdAndDeviceUserId(deviceId, deviceUserId)
                .filter(enrollment -> awaiting(enrollment.isResolved(), enrollment.getDecisionRevision(), revision))
                .ifPresent(enrollment -> {
                    enrollment.noteVerificationError(stored);
                    enrollments.save(enrollment);
                });
    }

    private static boolean awaiting(boolean resolved, Long decisionRevision, long revision) {
        return !resolved && decisionRevision != null && decisionRevision <= revision;
    }

    /** The reconciled record is the desired record the reader just read back. */
    @Transactional
    public void reconcile(DesiredMemberProjection desired) {
        if (desired == null || !desired.isPresentOnReader()) {
            return;
        }
        DeviceReaderBaseline baseline = baselines
                .findByDeviceIdAndDeviceUserId(desired.getDeviceId(), desired.getDeviceUserId())
                .orElseGet(() -> new DeviceReaderBaseline(
                        desired.getTenantId(), desired.getDeviceId(), desired.getDeviceUserId()));
        copyBaseline(baseline, desired);
        baselines.save(baseline);
    }

    private static void copyBaseline(DeviceReaderBaseline baseline, DesiredMemberProjection desired) {
        baseline.setReaderName(desired.getReaderName());
        baseline.setReaderNameEx(desired.getReaderNameEx());
        baseline.setUserStatus(desired.getUserStatus());
        baseline.setValidFrom(desired.getValidFrom());
        baseline.setValidTo(desired.getValidTo());
        baseline.setAuthority(desired.getAuthority());
        baseline.setFaceSha256(serverFace(desired));
    }

    /** What one side holds for a reader user, in the form an observation is compared with. */
    private record Held(String name, String authority, String validFrom, String validTo,
                        int userStatus, String faceSha256) {
    }

    private static Held held(DesiredMemberProjection desired) {
        return new Held(shown(desired.getReaderName(), desired.getReaderNameEx()), authority(desired.getAuthority()),
                desired.getValidFrom(), desired.getValidTo(), desired.getUserStatus(), serverFace(desired));
    }

    private static Held held(DeviceReaderBaseline baseline) {
        return new Held(shown(baseline.getReaderName(), baseline.getReaderNameEx()), authority(baseline.getAuthority()),
                baseline.getValidFrom(), baseline.getValidTo(), baseline.getUserStatus(), baseline.getFaceSha256());
    }

    private static String serverFace(DesiredMemberProjection desired) {
        return desired.isFacePresent() ? desired.getFaceSha256() : null;
    }

    /**
     * Name and authority always count. Validity, enabled or frozen, and the photo count only when
     * the observation includes them; validity only as a calendar day, so a clock suffix on the
     * same day is not a difference.
     */
    private static boolean agrees(JsonNode payload, Held other) {
        Integer readerStatus = status(payload);
        String readerFace = face(payload);
        return shown(text(payload, "name"), text(payload, "nameEx")).equals(other.name())
                && authority(text(payload, "authority")).equals(other.authority())
                && validityAgrees(payload, other.validFrom(), other.validTo())
                && (readerStatus == null || readerStatus == other.userStatus())
                && (readerFace == null || readerFace.equalsIgnoreCase(other.faceSha256()));
    }

    private static boolean baselineAgrees(DeviceReaderBaseline baseline, DesiredMemberProjection desired) {
        return shown(baseline.getReaderName(), baseline.getReaderNameEx())
                .equals(shown(desired.getReaderName(), desired.getReaderNameEx()))
                && authority(baseline.getAuthority()).equals(authority(desired.getAuthority()))
                && sameDay(baseline.getValidFrom(), desired.getValidFrom())
                && sameDay(baseline.getValidTo(), desired.getValidTo())
                && baseline.getUserStatus() == desired.getUserStatus()
                && sameFace(baseline.getFaceSha256(), serverFace(desired));
    }

    private static boolean sameFace(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static Integer status(JsonNode payload) {
        JsonNode value = payload == null ? null : payload.get("userStatus");
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static String face(JsonNode payload) {
        String value = text(payload, "faceSha256");
        return value != null && value.length() == 64 ? value : null;
    }

    private static boolean validityAgrees(JsonNode payload, String otherFrom, String otherTo) {
        boolean fromReported = reported(text(payload, "validFrom"));
        boolean toReported = reported(text(payload, "validTo"));
        if (!fromReported && !toReported) {
            return true;
        }
        if (fromReported && !sameDay(text(payload, "validFrom"), otherFrom)) {
            return false;
        }
        return !toReported || sameDay(text(payload, "validTo"), otherTo);
    }

    private static boolean reported(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean sameDay(String left, String right) {
        LocalDate first = day(left);
        LocalDate second = day(right);
        if (first == null || second == null) {
            return first == null && second == null;
        }
        return first.equals(second);
    }

    /**
     * The reader clock is India local time. A date-only value is that calendar day. An offset
     * instant, including Zulu, is converted to that zone before the day is taken.
     */
    private static LocalDate day(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text);
            }
            return OffsetDateTime.parse(text).atZoneSameInstant(READER_ZONE).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static String shown(String name, String nameEx) {
        if (nameEx != null && !nameEx.isBlank()) {
            return nameEx.trim();
        }
        return name == null ? "" : name.trim();
    }

    static String authority(String value) {
        if (value == null || value.isBlank()) {
            return "Customer";
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("USER") || trimmed.equalsIgnoreCase("Customer")) {
            return "Customer";
        }
        return trimmed;
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

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
