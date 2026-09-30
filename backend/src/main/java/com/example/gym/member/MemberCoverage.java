package com.example.gym.member;

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
 * or no plan. This is independent of {@link MemberStatus}, which only records whether the
 * website account is deactivated.
 */
@Component
public class MemberCoverage {

    private final MembershipRepository membershipRepository;

    public MemberCoverage(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public String of(Member member) {
        return of(membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId()));
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
        for (Member member : members) {
            coverage.put(member.getId(), of(grouped.getOrDefault(member.getId(), List.of())));
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
}
