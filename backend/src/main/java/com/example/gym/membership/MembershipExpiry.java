package com.example.gym.membership;

import com.example.gym.common.logging.FlowLog;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes EXPIRED onto plans whose end date has passed, so stored status matches the badge. */
@Service
public class MembershipExpiry {

    private final MembershipRepository membershipRepository;

    public MembershipExpiry(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public int expireElapsed() {
        LocalDate today = LocalDate.now();
        List<Membership> elapsed = membershipRepository.findByDeletedFalseAndStatusInAndEndDateBefore(
                List.of(MembershipStatus.ACTIVE, MembershipStatus.PENDING), today);
        int updated = 0;
        for (Membership membership : elapsed) {
            if (membership.effectiveStatus(today) == MembershipStatus.EXPIRED) {
                membership.setStatus(MembershipStatus.EXPIRED);
                updated++;
            }
        }
        if (updated > 0) {
            FlowLog.info("membership", "marked {} elapsed membership(s) expired", updated);
        }
        return updated;
    }
}
