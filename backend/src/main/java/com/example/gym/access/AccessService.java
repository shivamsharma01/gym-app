package com.example.gym.access;

import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a member's access decision for the staff UI. Door enablement for a member with no plan
 * follows reader / adopted validity (same as desired state); memberships tighten that when present.
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
                .findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId());
        if (memberships.isEmpty()) {
            // Matches DesiredProjectionService: no plan keeps the member enabled on reader dates.
            return build(member, null, today, true, AccessReason.READER_VALIDITY);
        }

        Optional<Membership> current = memberships.stream()
                .filter(m -> m.getStatus() != MembershipStatus.CANCELLED)
                .filter(m -> m.coversDate(today))
                .max(Comparator.comparing(Membership::getEndDate));

        if (current.isEmpty()) {
            // Plans exist but none cover today: doors still follow reader validity when set.
            if (readerValidityAllows(member, today)) {
                return build(member, null, today, true, AccessReason.READER_VALIDITY_NO_ACTIVE_PLAN);
            }
            return deny(member, null, today, AccessReason.NO_ACTIVE_MEMBERSHIP);
        }

        Membership m = current.get();
        MembershipStatus eff = m.effectiveStatus(today);
        return switch (eff) {
            case FROZEN -> deny(member, m, today, AccessReason.MEMBERSHIP_FROZEN);
            case EXPIRED -> deny(member, m, today, AccessReason.MEMBERSHIP_EXPIRED);
            case CANCELLED -> deny(member, m, today, AccessReason.MEMBERSHIP_CANCELLED);
            case ACTIVE -> allow(member, m, today);
            case PENDING -> {
                if (readerValidityAllows(member, today)) {
                    yield build(member, m, today, true, AccessReason.READER_VALIDITY_NO_ACTIVE_PLAN);
                }
                yield deny(member, m, today, AccessReason.NO_ACTIVE_MEMBERSHIP);
            }
        };
    }

    /** Adopted GetFace window or last device window staff/server sent. */
    private static boolean readerValidityAllows(Member member, LocalDate today) {
        LocalDate from = day(member.getAdoptedValidFrom());
        LocalDate to = day(member.getAdoptedValidTo());
        if (from == null || to == null) {
            from = member.getDeviceValidFrom();
            to = member.getDeviceValidTo();
        }
        if (from == null || to == null) {
            return false;
        }
        return !today.isBefore(from) && !today.isAfter(to);
    }

    private static LocalDate day(String readerTime) {
        if (readerTime == null || readerTime.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(readerTime).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(readerTime.length() >= 10 ? readerTime.substring(0, 10) : readerTime);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
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
                m == null ? null : m.getPaymentStatus().name());
    }
}
