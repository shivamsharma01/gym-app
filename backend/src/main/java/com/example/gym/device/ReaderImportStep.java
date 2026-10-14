package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One person of a bulk import. Every reader of the gym is locked first, lowest id first, so two
 * imports, or an import and a seed, take turns, and the member check reads what the other one
 * committed. A person who may already be a member is not created: staff link them, or create them
 * one by one on the Review page.
 */
@Service
public class ReaderImportStep {

    private final ReaderReconcileService readers;
    private final MemberMatcher matcher;
    private final DeviceObservedUserRepository observedUsers;
    private final ReviewDecisionService decisions;

    public ReaderImportStep(ReaderReconcileService readers,
                            MemberMatcher matcher,
                            DeviceObservedUserRepository observedUsers,
                            ReviewDecisionService decisions) {
        this.readers = readers;
        this.matcher = matcher;
        this.observedUsers = observedUsers;
        this.decisions = decisions;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewItemView createUnlessKnown(PendingEnrollment enrollment, Long tenantId) {
        readers.lockReaders(tenantId);
        if (ReviewListService.waitingForReader(enrollment)) {
            return decisions.create(enrollment.getPublicId(), tenantId);
        }
        observedUsers.findByDeviceIdAndDeviceUserId(enrollment.getDeviceId(), enrollment.getDeviceUserId())
                .flatMap(observed -> matcher.existing(tenantId, observed))
                .ifPresent(member -> {
                    throw CommonExceptions.conflict("May already be member " + member.getMemberCode() + " ("
                            + member.getFullName() + "); link them or create them one by one on the Review page");
                });
        return decisions.create(enrollment.getPublicId(), tenantId);
    }
}
