package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.DesiredProjectionService.Located;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.dto.DesiredStateRequests.ReportOccupant;
import com.example.gym.device.dto.DesiredStateRequests.RevisionNotice;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The gateway was told what a reader id should hold before a revision is written, found something
 * else, and wrote nothing. The server decides what that means; the gateway never does.
 */
@Service
public class ReaderOccupantService {

    private final DesiredProjectionService desired;
    private final PendingEnrollmentService observations;
    private final ReaderReviewService reviews;
    private final PendingEnrollmentRepository enrollments;

    public ReaderOccupantService(DesiredProjectionService desired,
                                 PendingEnrollmentService observations,
                                 ReaderReviewService reviews,
                                 PendingEnrollmentRepository enrollments) {
        this.desired = desired;
        this.observations = observations;
        this.reviews = reviews;
        this.enrollments = enrollments;
    }

    /**
     * A link, create or reject still waiting for the reader is undone and its enrollment reopens.
     * Otherwise someone else entirely suspends the member on this reader; a changed or missing
     * record of the member holds the row and opens the member's review on this reader.
     */
    @Transactional
    public RevisionNotice report(Gateway gateway, ReportOccupant report) {
        Located located = desired.locate(gateway, report.deviceId(), report.revision());
        Device device = located.device();
        DesiredMemberProjection row = located.row();
        if (!report.deviceUserId().equals(row.getDeviceUserId())) {
            throw CommonExceptions.conflict("Reported id is not the desired id");
        }
        ObjectNode payload = Boolean.TRUE.equals(report.present()) ? payload(report) : null;
        if (row.getMemberId() == null || awaitingEnrollmentDecision(device, row)) {
            observations.decisionOvertaken(device, row, payload);
        } else if (payload == null) {
            observations.occupantGone(device, row);
        } else {
            ExpectedOccupant known = row.getExpected() != null
                    ? row.getExpected()
                    : reviews.baseline(device.getId(), row.getDeviceUserId()).map(ExpectedOccupant::of).orElse(null);
            observations.occupantChanged(device, row, known, payload);
        }
        FlowLog.info("device", "reader={} user={} revision={} not written: the reader shows {}",
                device.getPublicId(), report.deviceUserId(), report.revision(),
                payload == null ? "no one" : "a different record");
        return new RevisionNotice(device.getPublicId(), report.revision());
    }

    private boolean awaitingEnrollmentDecision(Device device, DesiredMemberProjection row) {
        return enrollments.findByDeviceIdAndDeviceUserId(device.getId(), row.getDeviceUserId())
                .filter(enrollment -> !enrollment.isResolved())
                .map(PendingEnrollment::getDecisionRevision)
                .isPresent();
    }

    private static ObjectNode payload(ReportOccupant report) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("deviceUserId", report.deviceUserId());
        node.put("name", report.name());
        node.put("nameEx", report.nameEx());
        node.put("userStatus", report.userStatus());
        node.put("validFrom", report.validFrom());
        node.put("validTo", report.validTo());
        node.put("authority", report.authority());
        node.put("doorNum", report.doorNum());
        node.put("timeSectionNum", report.timeSectionNum());
        node.put("faceSha256", report.faceSha256());
        return node;
    }
}
