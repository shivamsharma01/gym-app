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
 * committed. How a possible match is treated depends on {@link Mode}.
 */
@Service
public class ReaderImportStep {

    /**
     * {@link #CONSERVATIVE} (default import): same photo or same name holds the person for Review.
     * {@link #DISTINCT_SOURCE}: each reader id is its own identity; only the same photo holds them
     * (staff link). Same name alone creates a second member. Not a blanket bypass of photo or
     * already-decided rules.
     */
    public enum Mode {
        CONSERVATIVE,
        DISTINCT_SOURCE
    }

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
        return createUnlessKnown(enrollment, tenantId, Mode.CONSERVATIVE);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewItemView createUnlessKnown(PendingEnrollment enrollment, Long tenantId, Mode mode) {
        readers.lockReaders(tenantId);
        if (ReviewListService.waitingForReader(enrollment)) {
            return decisions.create(enrollment.getPublicId(), tenantId);
        }
        observedUsers.findByDeviceIdAndDeviceUserId(enrollment.getDeviceId(), enrollment.getDeviceUserId())
                .ifPresent(observed -> {
                    var match = mode == Mode.DISTINCT_SOURCE
                            ? matcher.existingByFace(tenantId, observed)
                            : matcher.existing(tenantId, observed);
                    match.ifPresent(member -> {
                        throw CommonExceptions.conflict("May already be member " + member.getMemberCode() + " ("
                                + member.getFullName() + "); link them or create them one by one on the Review page");
                    });
                });
        return decisions.create(enrollment.getPublicId(), tenantId);
    }
}
