package com.example.gym.device;

import com.example.gym.member.Member;
import com.example.gym.member.MemberCreationSource;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.DeviceSyncState;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipPaymentStatus;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.plan.MembershipPlan;
import com.example.gym.plan.MembershipPlanRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates a Member (DEVICE_IMPORT) plus a fallback-plan membership from a user found on a device.
 * Shared by the manual roster import and live device-change handling.
 */
@Component
public class DeviceMemberImporter {

    public static final String UNKNOWN_PLAN_NAME = "Fallback Membership plan for quick access";

    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final MembershipPlanRepository planRepository;

    public DeviceMemberImporter(MemberRepository memberRepository,
                                MembershipRepository membershipRepository,
                                MembershipPlanRepository planRepository) {
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
    }

    public Member createMember(Long tenantId, String deviceUserId, String deviceName, boolean frozen) {
        String code = truncateCode(deviceUserId);
        if (memberRepository.existsByTenantIdAndMemberCode(tenantId, code)) {
            code = truncateCode("DEV-" + deviceUserId);
        }
        NameParts names = parseName(deviceName, deviceUserId);
        Member member = new Member(tenantId, code, names.firstName());
        member.setLastName(names.lastName());
        member.setCreationSource(MemberCreationSource.DEVICE_IMPORT);
        member.setStatus(frozen ? MemberStatus.INACTIVE : MemberStatus.ACTIVE);
        return memberRepository.save(member);
    }

    /**
     * Adds a fallback-plan membership from the device validity when the member has no current
     * membership. Returns true when the end date had to be inferred. No MembershipChangedEvent is
     * published — the device already holds this user.
     */
    public boolean ensureUnknownMembership(Member member, LocalDate validFrom, LocalDate validTo) {
        LocalDate today = LocalDate.now();
        boolean hasCurrent = membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId())
                .stream()
                .filter(m -> m.getStatus() != MembershipStatus.CANCELLED)
                .anyMatch(m -> m.coversDate(today));
        if (hasCurrent) {
            return false;
        }
        MembershipPlan plan = ensureUnknownPlan(member.getTenantId());
        LocalDate start = validFrom != null ? validFrom : today;
        boolean inferred = validTo == null;
        LocalDate end = inferred ? today.plusYears(1) : validTo;
        if (end.isBefore(start)) {
            end = start.plusYears(1);
            inferred = true;
        }
        Membership membership = new Membership(
                member.getTenantId(), member.getId(), plan.getId(), plan.getName(), plan.getPrice(),
                plan.getCurrency(), start, end, MembershipStatus.ACTIVE);
        membership.setPaymentStatus(MembershipPaymentStatus.PAID);
        membership.setAmountPaid(BigDecimal.ZERO);
        membership.setEndDateInferred(inferred);
        membership.setDeviceSyncState(DeviceSyncState.SYNCED);
        membershipRepository.save(membership);
        return inferred;
    }

    public MembershipPlan ensureUnknownPlan(Long tenantId) {
        return planRepository.findFirstByTenantIdAndNameIgnoreCase(tenantId, UNKNOWN_PLAN_NAME)
                .orElseGet(() -> planRepository.save(new MembershipPlan(
                        tenantId,
                        UNKNOWN_PLAN_NAME,
                        "Placeholder plan for members imported from devices; replace with the correct plan later.",
                        BigDecimal.ZERO,
                        "INR",
                        365)));
    }

    public static String truncateCode(String raw) {
        String code = raw == null ? "UNKNOWN" : raw.trim();
        return code.length() > 32 ? code.substring(0, 32) : code;
    }

    public static NameParts parseName(String deviceName, String fallbackId) {
        String raw = StringUtils.hasText(deviceName) ? deviceName.trim() : fallbackId;
        int space = raw.indexOf(' ');
        if (space < 0) {
            return new NameParts(raw, null);
        }
        return new NameParts(raw.substring(0, space), raw.substring(space + 1).trim());
    }

    /** Device user names are capped at 31 characters. */
    public static boolean sameDeviceName(String deviceName, String fullName) {
        if (!StringUtils.hasText(deviceName)) {
            return true;
        }
        String full = fullName == null ? "" : fullName.trim();
        String capped = full.length() > 31 ? full.substring(0, 31) : full;
        return capped.equals(deviceName.trim());
    }

    public record NameParts(String firstName, String lastName) {
    }
}
