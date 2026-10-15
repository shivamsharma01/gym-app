package com.example.gym.device;

import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Review page list: one item per reader per conflict episode, and one per person a reader
 * shows that the server does not know. An enrollment names the member it may already be, so staff
 * link instead of creating a second member. Nothing is linked here.
 */
@Service
public class ReviewListService {

    private final ReviewDecisionService decisions;
    private final PendingEnrollmentRepository enrollments;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberMatcher matcher;

    public ReviewListService(ReviewDecisionService decisions,
                             PendingEnrollmentRepository enrollments,
                             DeviceObservedUserRepository observedUsers,
                             MemberMatcher matcher) {
        this.decisions = decisions;
        this.enrollments = enrollments;
        this.observedUsers = observedUsers;
        this.matcher = matcher;
    }

    @Transactional(readOnly = true)
    public List<ReviewItemView> open(Long tenantId) {
        Map<String, PendingEnrollment> undecided = enrollments.findByTenantIdAndResolvedFalse(tenantId).stream()
                .collect(Collectors.toMap(PendingEnrollment::getPublicId, Function.identity()));
        List<ReviewItemView> rows = new ArrayList<>();
        for (ReviewItemView view : decisions.open(tenantId)) {
            PendingEnrollment enrollment = undecided.get(view.id());
            if (enrollment == null || waitingForReader(enrollment)) {
                rows.add(view);
                continue;
            }
            rows.add(observedUsers.findByDeviceIdAndDeviceUserId(enrollment.getDeviceId(), enrollment.getDeviceUserId())
                    .flatMap(observed -> matcher.existing(tenantId, observed))
                    .map(member -> view.withPossibleMember(member.getPublicId(), member.getFullName()))
                    .orElse(view));
        }
        return rows;
    }

    /** A decision is on its way to the reader; the member it names is not a second candidate. */
    static boolean waitingForReader(PendingEnrollment enrollment) {
        return enrollment.getDecision() != null
                && (enrollment.getVerificationError() == null || enrollment.getVerificationError().isBlank());
    }
}
