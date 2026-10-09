package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Audits a review or enrollment decision. Call it only after the decision has committed:
 * {@link AuditService} writes in its own transaction, so an earlier call would leave a success
 * row behind a decision that rolled back.
 */
@Component
public class ReviewDecisionAudit {

    private final AuditService audit;
    private final DeviceReviewItemRepository reviews;
    private final MemberRepository members;

    public ReviewDecisionAudit(AuditService audit, DeviceReviewItemRepository reviews, MemberRepository members) {
        this.audit = audit;
        this.reviews = reviews;
        this.members = members;
    }

    public ReviewItemView decided(ReviewItemView view) {
        boolean enrollment = ReviewDecisions.ENROLLMENT.equals(view.kind());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("decision", view.decision());
        details.put("deviceId", view.deviceId());
        details.put("deviceUserId", view.deviceUserId());
        details.put("revision", view.revision());
        details.put("priorState", view.priorState());
        details.put("chosenState", view.chosenState());
        String memberId = enrollment ? enrollmentMember(view) : reviewMember(view.id());
        if (memberId != null) {
            details.put("memberId", memberId);
        }
        audit.record(enrollment ? AuditActions.ENROLLMENT_DECIDED : AuditActions.REVIEW_DECIDED,
                AuditActions.RESULT_SUCCESS,
                enrollment ? "PendingEnrollment" : "DeviceReviewItem",
                view.id(), details);
        return view;
    }

    private static String enrollmentMember(ReviewItemView view) {
        boolean member = ReviewDecisions.LINK.equals(view.decision()) || ReviewDecisions.CREATE.equals(view.decision());
        return member ? view.chosenState() : null;
    }

    private String reviewMember(String reviewId) {
        return reviews.findByPublicId(reviewId)
                .map(DeviceReviewItem::getMemberId)
                .flatMap(members::findById)
                .map(Member::getPublicId)
                .orElse(null);
    }
}
