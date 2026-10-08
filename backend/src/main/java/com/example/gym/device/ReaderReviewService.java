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
        String baselineName = baseline == null
                ? desired.getReaderName() : baseline.getReaderName();
        String baselineNameEx = baseline == null
                ? desired.getReaderNameEx() : baseline.getReaderNameEx();
        String storedBaselineAuthority = baseline == null
                ? desired.getAuthority() : baseline.getAuthority();

        String readerName = shown(text(payload, "name"), text(payload, "nameEx"));
        String serverName = shown(desired.getReaderName(), desired.getReaderNameEx());
        String comparedBaseline = shown(baselineName, baselineNameEx);
        String readerAuthority = authority(text(payload, "authority"));
        String serverAuthority = authority(desired.getAuthority());
        String baselineAuthority = authority(storedBaselineAuthority);

        if (readerName.equals(serverName) && readerAuthority.equals(serverAuthority)) {
            if (baseline != null
                    && (!comparedBaseline.equals(serverName) || !baselineAuthority.equals(serverAuthority))) {
                copyBaseline(baseline, desired);
                baselines.save(baseline);
            }
            return;
        }
        if (readerName.equals(comparedBaseline) && readerAuthority.equals(baselineAuthority)) {
            return;
        }

        DeviceReviewItem item = conflictFor(device, deviceUserId, desired.getMemberId());
        boolean thisReader = item.getId() == null || device.getId().equals(item.getDeviceId());
        if (thisReader && item.isResolved()) {
            item.clearDecision();
        }
        if (thisReader) {
            item.setBaselineName(baselineName);
            item.setBaselineNameEx(baselineNameEx);
            item.setBaselineAuthority(storedBaselineAuthority);
            item.setServerName(desired.getReaderName());
            item.setServerNameEx(desired.getReaderNameEx());
            item.setServerAuthority(desired.getAuthority());
            item.setReaderName(cut(text(payload, "name"), 127));
            item.setReaderNameEx(cut(text(payload, "nameEx"), 127));
            item.setReaderAuthority(cut(text(payload, "authority"), 32));
            item.setObservedAt(Instant.now());
        }
        item = reviews.save(item);
        rememberSnapshot(item, device, deviceUserId, payload);
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
            item.setServerName(desired.getReaderName());
            item.setServerNameEx(desired.getReaderNameEx());
            item.setServerAuthority(desired.getAuthority());
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
        snapshot.setObservedAt(Instant.now());
        snapshots.save(snapshot);
    }

    /**
     * The gateway acknowledged this revision. The staff decision that published it is closed.
     */
    @Transactional
    public void settle(Long deviceId, long revision) {
        reviews.findByDeviceIdAndDecisionRevision(deviceId, revision).ifPresent(item -> {
            item.resolve();
            reviews.save(item);
        });
        enrollments.findByDeviceIdAndDecisionRevision(deviceId, revision).ifPresent(enrollment -> {
            enrollment.resolve();
            enrollments.save(enrollment);
        });
    }

    /**
     * Read-back did not match. The decision stays open and keeps the verification error.
     * Commits on its own so the error survives the rejected acknowledgement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void noteVerificationFailure(Long deviceId, long revision, String error) {
        String stored = cut(error, 255);
        reviews.findByDeviceIdAndDecisionRevision(deviceId, revision).ifPresent(item -> {
            item.noteVerificationError(stored);
            reviews.save(item);
        });
        enrollments.findByDeviceIdAndDecisionRevision(deviceId, revision).ifPresent(enrollment -> {
            enrollment.noteVerificationError(stored);
            enrollments.save(enrollment);
        });
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
