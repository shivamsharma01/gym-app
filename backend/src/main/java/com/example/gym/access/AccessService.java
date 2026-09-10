package com.example.gym.access;

import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipPaymentStatus;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a member's <em>business</em> access decision from their memberships. This does not talk
 * to any device; it is the authoritative "allowed / denied" that the device sync engine (Phase 3)
 * will later enforce on the hardware.
 */
@Service
public class AccessService {

    private final MemberService memberService;
    private final MembershipRepository membershipRepository;

    public AccessService(MemberService memberService, MembershipRepository membershipRepository) {
        this.memberService = memberService;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public AccessStatusResponse decide(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        LocalDate today = LocalDate.now();

        if (member.getStatus() == MemberStatus.INACTIVE) {
            return deny(member, null, today, AccessReason.MEMBER_INACTIVE);
        }

        List<Membership> memberships = membershipRepository
                .findByMemberIdOrderByStartDateDesc(member.getId());
        if (memberships.isEmpty()) {
            return deny(member, null, today, AccessReason.NO_MEMBERSHIP);
        }

        Optional<Membership> current = memberships.stream()
                .filter(m -> m.getStatus() != MembershipStatus.CANCELLED)
                .filter(m -> m.coversDate(today))
                .max(Comparator.comparing(Membership::getEndDate));

        if (current.isEmpty()) {
            return deny(member, null, today, AccessReason.NO_ACTIVE_MEMBERSHIP);
        }

        Membership m = current.get();
        MembershipStatus eff = m.effectiveStatus(today);
        return switch (eff) {
            case FROZEN -> deny(member, m, today, AccessReason.MEMBERSHIP_FROZEN);
            case EXPIRED -> deny(member, m, today, AccessReason.MEMBERSHIP_EXPIRED);
            case CANCELLED -> deny(member, m, today, AccessReason.MEMBERSHIP_CANCELLED);
            case ACTIVE -> m.getPaymentStatus() == MembershipPaymentStatus.UNPAID
                    ? deny(member, m, today, AccessReason.PAYMENT_OVERDUE)
                    : allow(member, m, today);
            case PENDING -> deny(member, m, today, AccessReason.NO_ACTIVE_MEMBERSHIP);
        };
    }

    private AccessStatusResponse allow(Member member, Membership m, LocalDate today) {
        return build(member, m, today, true, AccessReason.ALLOWED);
    }

    private AccessStatusResponse deny(Member member, Membership m, LocalDate today, AccessReason reason) {
        return build(member, m, today, false, reason);
    }

    private AccessStatusResponse build(Member member, Membership m, LocalDate today,
                                       boolean allowed, AccessReason reason) {
        return new AccessStatusResponse(
                member.getPublicId(),
                allowed,
                reason.name(),
                m == null ? null : m.getPublicId(),
                m == null ? null : m.effectiveStatus(today).name(),
                m == null ? null : m.getEndDate(),
                m == null ? null : m.getPaymentStatus().name(),
                m == null ? null : m.getDeviceSyncState().name());
    }
}
