package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One person or a small batch of a bulk import. Every reader of the gym is locked once per batch,
 * lowest id first, so two imports, or an import and a seed, take turns, and the member check reads
 * what the other one committed. How a possible match is treated depends on {@link Mode}.
 */
@Service
public class ReaderImportStep {

    /** How many creates share one reader-lock transaction. */
    static final int BATCH_SIZE = 25;

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

    public record BatchResult(List<ReviewItemView> created, List<ReaderImportService.Skipped> skipped) {
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
        Optional<String> hold = holdReason(enrollment, tenantId, mode);
        if (hold.isPresent()) {
            throw CommonExceptions.conflict(hold.get());
        }
        return decisions.create(enrollment.getPublicId(), tenantId);
    }

    /**
     * Locks all gym readers once, then creates up to {@link #BATCH_SIZE} people (or records a skip
     * reason without throwing, so one hold does not abort the batch transaction).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchResult createBatch(List<PendingEnrollment> batch, Long tenantId, Mode mode) {
        readers.lockReaders(tenantId);
        List<ReviewItemView> created = new ArrayList<>();
        List<ReaderImportService.Skipped> skipped = new ArrayList<>();
        for (PendingEnrollment enrollment : batch) {
            Optional<String> hold = holdReason(enrollment, tenantId, mode);
            if (hold.isPresent()) {
                skipped.add(new ReaderImportService.Skipped(enrollment.getDeviceUserId(), hold.get()));
                continue;
            }
            created.add(decisions.create(enrollment.getPublicId(), tenantId));
        }
        return new BatchResult(List.copyOf(created), List.copyOf(skipped));
    }

    private Optional<String> holdReason(PendingEnrollment enrollment, Long tenantId, Mode mode) {
        // Already decided, ack still pending — leave them; do not call create again.
        if (ReviewListService.waitingForReader(enrollment)) {
            return Optional.of("Decision is waiting for the reader");
        }
        var observed = observedUsers
                .findByDeviceIdAndDeviceUserId(enrollment.getDeviceId(), enrollment.getDeviceUserId())
                .orElse(null);
        if (observed == null) {
            return Optional.of("Reader observation is missing");
        }
        if (observed.getFaceSha256() == null || observed.getFaceSha256().length() != 64) {
            return Optional.of("The reader face is not on the server");
        }
        var match = mode == Mode.DISTINCT_SOURCE
                ? matcher.existingByFace(tenantId, observed)
                : matcher.existing(tenantId, observed);
        return match.map(member -> "May already be member " + member.getMemberCode() + " ("
                + member.getFullName() + "); link them or create them one by one on the Review page");
    }
}
