package com.example.gym.device;

import com.example.gym.common.error.ApiException;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.PendingEnrollment;
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
 * member holds what the reader already shows, so nothing is written to the reader. A person whose
 * photo has not reached the server, or whose decision is still waiting for the reader, stays
 * pending and is reported. Nobody is linked, and no other reader is touched.
 */
@Service
public class ReaderImportService {

    private final DeviceRepository devices;
    private final PendingEnrollmentRepository enrollments;
    private final ReviewDecisionService decisions;
    private final ReviewDecisionAudit audit;

    public ReaderImportService(DeviceRepository devices,
                               PendingEnrollmentRepository enrollments,
                               ReviewDecisionService decisions,
                               ReviewDecisionAudit audit) {
        this.devices = devices;
        this.enrollments = enrollments;
        this.decisions = decisions;
        this.audit = audit;
    }

    public record Skipped(String deviceUserId, String reason) {
    }

    public record Imported(String deviceId, int created, List<Skipped> skipped) {
    }

    public Imported importPending(String devicePublicId, Long tenantId) {
        Device reader = devices.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(reader.getTenantId(), tenantId, "Device");
        if (reader.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        List<PendingEnrollment> pending = enrollments.findByDeviceIdAndResolvedFalse(reader.getId()).stream()
                .sorted(Comparator.comparing(PendingEnrollment::getId))
                .toList();
        int created = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (PendingEnrollment enrollment : pending) {
            try {
                audit.decided(decisions.create(enrollment.getPublicId(), tenantId));
                created++;
            } catch (ApiException e) {
                skipped.add(new Skipped(enrollment.getDeviceUserId(), e.getMessage()));
            }
        }
        FlowLog.info("device", "imported reader={} created={} skipped={}",
                reader.getPublicId(), created, skipped.size());
        return new Imported(reader.getPublicId(), created, List.copyOf(skipped));
    }
}
