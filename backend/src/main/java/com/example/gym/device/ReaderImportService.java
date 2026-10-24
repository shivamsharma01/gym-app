package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.ReaderImportStep.Mode;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.tenant.TenantGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Staff turn every person one reader showed, and nobody has decided on yet, into a member. Each
 * person is one create decision in its own transaction, audited like a single create, and the
 * member holds what the reader already shows, so nothing is written to the reader. A person another
 * reader already shows with the same photo, name, dates and access is linked there automatically.
 * {@link Mode#CONSERVATIVE} leaves a different name or photo pending; {@link Mode#DISTINCT_SOURCE}
 * creates a member per reader id unless the photo already belongs to a member.
 */
@Service
public class ReaderImportService {

    private final DeviceRepository devices;
    private final PendingEnrollmentRepository enrollments;
    private final SeenMemberClaim claims;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberMatcher matcher;
    private final ReaderImportStep step;
    private final ReviewDecisionAudit audit;

    public ReaderImportService(DeviceRepository devices,
                               PendingEnrollmentRepository enrollments,
                               SeenMemberClaim claims,
                               DeviceObservedUserRepository observedUsers,
                               MemberMatcher matcher,
                               ReaderImportStep step,
                               ReviewDecisionAudit audit) {
        this.devices = devices;
        this.enrollments = enrollments;
        this.claims = claims;
        this.observedUsers = observedUsers;
        this.matcher = matcher;
        this.step = step;
        this.audit = audit;
    }

    public record Skipped(String deviceUserId, String reason) {
    }

    /**
     * Counts for one reader after an import (or a preview). Partition of undecided pending when
     * reasons are exclusive; {@code withPhoto} / {@code withoutPhoto} are not a second partition.
     */
    public record Summary(
            String deviceId,
            int observed,
            int withPhoto,
            int withoutPhoto,
            int pendingUndecided,
            int possibleNameMatch,
            int possibleFaceMatch,
            int readyToCreate,
            int created,
            int skipped,
            String mode) {
    }

    public record Imported(String deviceId, int created, List<Skipped> skipped, Summary summary) {
    }

    public Imported importPending(String devicePublicId, Long tenantId) {
        return importPending(devicePublicId, tenantId, Mode.CONSERVATIVE);
    }

    public Imported importPending(String devicePublicId, Long tenantId, Mode mode) {
        Device reader = requireReader(devicePublicId, tenantId);
        Mode chosen = mode == null ? Mode.CONSERVATIVE : mode;
        List<PendingEnrollment> pending = enrollments.findByDeviceIdAndResolvedFalse(reader.getId()).stream()
                .sorted(Comparator.comparing(PendingEnrollment::getId))
                .toList();
        int created = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (int from = 0; from < pending.size(); from += ReaderImportStep.BATCH_SIZE) {
            int to = Math.min(from + ReaderImportStep.BATCH_SIZE, pending.size());
            ReaderImportStep.BatchResult batch = step.createBatch(pending.subList(from, to), tenantId, chosen);
            for (var item : batch.created()) {
                audit.decided(item);
            }
            created += batch.created().size();
            skipped.addAll(batch.skipped());
        }
        claims.claimQuiet(reader.getTenantId());
        Summary summary = summarize(reader, chosen, created, skipped.size());
        FlowLog.info("device", "imported reader={} mode={} created={} skipped={}",
                reader.getPublicId(), chosen, created, skipped.size());
        return new Imported(reader.getPublicId(), created, List.copyOf(skipped), summary);
    }

    /** What import would do now, without creating anyone. */
    public Summary preview(String devicePublicId, Long tenantId, Mode mode) {
        Device reader = requireReader(devicePublicId, tenantId);
        return summarize(reader, mode == null ? Mode.CONSERVATIVE : mode, 0, 0);
    }

    private Device requireReader(String devicePublicId, Long tenantId) {
        Device reader = devices.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(reader.getTenantId(), tenantId, "Device");
        if (reader.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        return reader;
    }

    private Summary summarize(Device reader, Mode mode, int created, int skipped) {
        List<DeviceObservedUser> people = observedUsers.findByDeviceId(reader.getId());
        int withPhoto = 0;
        for (DeviceObservedUser person : people) {
            if (person.getFaceSha256() != null && person.getFaceSha256().length() == 64) {
                withPhoto++;
            }
        }
        List<PendingEnrollment> undecided = enrollments.findByDeviceIdAndResolvedFalse(reader.getId()).stream()
                .filter(enrollment -> enrollment.getDecision() == null)
                .toList();
        int nameMatch = 0;
        int faceMatch = 0;
        int ready = 0;
        for (PendingEnrollment enrollment : undecided) {
            DeviceObservedUser observed = observedUsers
                    .findByDeviceIdAndDeviceUserId(reader.getId(), enrollment.getDeviceUserId())
                    .orElse(null);
            if (observed == null || observed.getFaceSha256() == null || observed.getFaceSha256().length() != 64) {
                continue;
            }
            boolean face = matcher.existingByFace(reader.getTenantId(), observed).isPresent();
            boolean name = matcher.existing(reader.getTenantId(), observed).isPresent() && !face;
            if (face) {
                faceMatch++;
            } else if (name) {
                nameMatch++;
            }
            if (mode == Mode.DISTINCT_SOURCE ? !face : !(face || name)) {
                ready++;
            }
        }
        return new Summary(
                reader.getPublicId(),
                people.size(),
                withPhoto,
                people.size() - withPhoto,
                undecided.size(),
                nameMatch,
                faceMatch,
                ready,
                created,
                skipped,
                mode.name());
    }
}
