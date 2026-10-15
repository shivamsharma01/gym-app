package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.DesiredProjectionService.Located;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayMessageDedupe;
import com.example.gym.device.dto.DesiredStateRequests.ObservationQuery;
import com.example.gym.device.dto.DesiredStateRequests.ObservationStatus;
import com.example.gym.device.dto.DesiredStateRequests.ReportFailed;
import com.example.gym.device.dto.DesiredStateRequests.RevisionNotice;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.GatewayMessageDedupeRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the gateway needs to know before it applies desired state, and what it could not apply.
 * Desired state for a reader waits until the server has processed that reader's latest observation;
 * a revision the gateway cannot apply is shown to staff and stays desired.
 */
@Service
public class ReaderApplyStatusService {

    private final DesiredProjectionService desired;
    private final GatewayMessageDedupeRepository processed;
    private final DesiredMemberProjectionRepository projections;
    private final ReaderReviewService reviews;

    public ReaderApplyStatusService(DesiredProjectionService desired,
                                    GatewayMessageDedupeRepository processed,
                                    DesiredMemberProjectionRepository projections,
                                    ReaderReviewService reviews) {
        this.desired = desired;
        this.processed = processed;
        this.projections = projections;
        this.reviews = reviews;
    }

    /**
     * True only when every message was processed successfully for this gateway and its record has
     * not expired. A message still queued, being processed, failed, or sent by another gateway is
     * not processed.
     */
    @Transactional(readOnly = true)
    public ObservationStatus observed(Gateway gateway, ObservationQuery query) {
        desired.ownedReader(gateway, query.deviceId());
        List<String> ids = List.copyOf(new HashSet<>(query.messageIds()));
        Map<String, GatewayMessageDedupe> found = processed.findAllById(ids).stream()
                .collect(Collectors.toMap(GatewayMessageDedupe::getMessageId, Function.identity()));
        Instant now = Instant.now();
        boolean all = ids.stream().allMatch(id -> {
            GatewayMessageDedupe entry = found.get(id);
            return entry != null && gateway.getId().equals(entry.getGatewayId()) && entry.getExpiresAt().isAfter(now);
        });
        return new ObservationStatus(all);
    }

    /**
     * Stored on the current row and noted on the decision waiting for it. A revision that is no
     * longer current is refused, so the gateway drops it.
     */
    @Transactional
    public RevisionNotice failed(Gateway gateway, ReportFailed report) {
        Located located = desired.locate(gateway, report.deviceId(), report.revision());
        Device device = located.device();
        DesiredMemberProjection row = located.row();
        if (report.deviceUserId() != null && !report.deviceUserId().equals(row.getDeviceUserId())) {
            throw CommonExceptions.conflict("Reported id is not the desired id");
        }
        boolean permanent = Boolean.TRUE.equals(report.permanent());
        row.failed(report.revision(), report.error(), report.attempts(), permanent);
        projections.save(row);
        reviews.noteVerificationFailure(device.getId(), row.getDeviceUserId(), row.getMemberId(),
                report.revision(), (permanent ? "The reader cannot apply this change: " : "The reader has not applied this change: ")
                        + report.error());
        FlowLog.warn("device", "reader={} user={} revision={} not applied after {} attempts ({}): {}",
                device.getPublicId(), row.getDeviceUserId(), report.revision(), report.attempts(),
                permanent ? "permanent" : "still retrying", report.error());
        return new RevisionNotice(device.getPublicId(), report.revision());
    }
}
