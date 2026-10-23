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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the gateway needs to know before it applies desired state, and what it could not apply.
 * Desired state for a reader waits until the server has processed that reader's latest observation;
 * a revision the gateway cannot apply is shown to staff and stays desired.
 */
@Service
public class ReaderApplyStatusService {

    private static final Logger log = LoggerFactory.getLogger(ReaderApplyStatusService.class);

    private final Map<String, String> lastReported = new ConcurrentHashMap<>();
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
        Device device = desired.ownedReader(gateway, query.deviceId());
        List<String> ids = List.copyOf(new HashSet<>(query.messageIds()));
        Map<String, GatewayMessageDedupe> found = processed.findAllById(ids).stream()
                .collect(Collectors.toMap(GatewayMessageDedupe::getMessageId, Function.identity()));
        Instant now = Instant.now();
        int saved = (int) ids.stream().filter(id -> saved(found.get(id), gateway, now)).count();
        int waiting = ids.size() - saved;
        boolean all = waiting == 0;
        report(device, saved, ids.size(), all);
        return new ObservationStatus(all, saved, waiting);
    }

    private static boolean saved(GatewayMessageDedupe entry, Gateway gateway, Instant now) {
        return entry != null && gateway.getId().equals(entry.getGatewayId()) && entry.getExpiresAt().isAfter(now);
    }

    /** One line when the count changes, so a long pause shows progress instead of silence. */
    private void report(Device device, int saved, int total, boolean done) {
        String value = saved + "/" + total + "/" + done;
        if (value.equals(lastReported.put(device.getPublicId(), value))) {
            return;
        }
        if (done) {
            log.info("{}: reader list is saved ({} updates). The gateway can write members onto the reader now.",
                    device.getName(), total);
            return;
        }
        log.info("{}: reader list is not finished. {} of {} updates are saved. Member sync stays paused until the rest are saved. Nothing to do in the app.",
                device.getName(), saved, total);
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
