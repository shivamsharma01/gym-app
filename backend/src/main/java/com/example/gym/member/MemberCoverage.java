package com.example.gym.member;

import com.example.gym.access.ReaderValidity;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Membership status for staff: a plan that covers today, otherwise the next plan, a lapsed plan,
 * or no plan. Payment is not part of this. This is independent of {@link MemberStatus}, which
 * records whether the account is deactivated.
 */
@Component
public class MemberCoverage {

    private final MembershipRepository membershipRepository;

    public MemberCoverage(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public String of(Member member) {
        return of(member, membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId()),
                LocalDate.now());
    }

    public Map<Long, String> ofAll(List<Member> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = members.stream().map(Member::getId).toList();
        Map<Long, List<Membership>> grouped = new HashMap<>();
        for (Membership membership : membershipRepository.findByMemberIdInAndDeletedFalse(ids)) {
            grouped.computeIfAbsent(membership.getMemberId(), id -> new java.util.ArrayList<>()).add(membership);
        }
        Map<Long, String> coverage = new HashMap<>();
        LocalDate today = LocalDate.now();
        for (Member member : members) {
            coverage.put(member.getId(), of(member, grouped.getOrDefault(member.getId(), List.of()), today));
        }
        return coverage;
    }

    static String of(List<Membership> memberships) {
        LocalDate today = LocalDate.now();
        boolean active = false;
        boolean frozen = false;
        boolean pending = false;
        boolean expired = false;
        for (Membership membership : memberships) {
            if (membership.isDeleted()) {
                continue;
            }
            switch (membership.effectiveStatus(today)) {
                case ACTIVE -> active = true;
                case FROZEN -> frozen = true;
                case PENDING -> pending = true;
                case EXPIRED -> expired = true;
                default -> {
                }
            }
        }
        if (active) {
            return MembershipStatus.ACTIVE.name();
        }
        if (frozen) {
            return MembershipStatus.FROZEN.name();
        }
        if (pending) {
            return MembershipStatus.PENDING.name();
        }
        if (expired) {
            return MembershipStatus.EXPIRED.name();
        }
        return "NO_PLAN";
    }

    /** No plan, but the dates copied from the reader have already ended. */
    private static String of(Member member, List<Membership> memberships, LocalDate today) {
        String coverage = of(memberships);
        if ("NO_PLAN".equals(coverage) && ReaderValidity.on(member, today) == ReaderValidity.State.EXPIRED) {
            return MembershipStatus.EXPIRED.name();
        }
        return coverage;
    }
}
