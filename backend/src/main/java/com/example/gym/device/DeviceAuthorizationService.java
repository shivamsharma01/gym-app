package com.example.gym.device;

import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides what a member's readers should hold — one validity window plus enabled or
 * disabled — and publishes that as a desired revision. Does not talk to hardware.
 * <p>
 * A device holds a single window, so it is taken from the membership that has not ended yet: the
 * one running today, else the next one. Memberships that run back to back (or overlap) and are
 * both usable are joined into one window. The device refuses entry outside the window by itself,
 * so gaps between memberships are closed without any command. Enabled means: member active, the
 * membership not frozen, and either started or the devices enforce the start date themselves.
 * Payment is bookkeeping and does not open or close the door. When the last membership has ended
 * the device is disabled and keeps its dates.
 */
@Service
public class DeviceAuthorizationService {

    /** What the devices should hold for a member. {@code first}/{@code last} are the memberships joined into it. */
    public record AccessWindow(Membership first, Membership last, LocalDate validFrom, LocalDate validTo,
                               boolean enabled) {
    }

    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final GatewayProperties properties;
    private final ObjectProvider<DesiredProjectionService> desiredState;

    public DeviceAuthorizationService(MemberRepository memberRepository,
                                      MembershipRepository membershipRepository,
                                      GatewayProperties properties,
                                      ObjectProvider<DesiredProjectionService> desiredState) {
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.properties = properties;
        this.desiredState = desiredState;
    }

    @Transactional(readOnly = true)
    public Optional<AccessWindow> window(Member member) {
        return window(member, LocalDate.now());
    }

    public Optional<AccessWindow> window(Member member, LocalDate today) {
        List<Membership> memberships = membershipRepository
                .findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId()).stream()
                .filter(m -> m.getStatus() != MembershipStatus.CANCELLED)
                .sorted(Comparator.comparing(Membership::getStartDate).thenComparing(Membership::getEndDate))
                .toList();
        if (memberships.isEmpty()) {
            return Optional.empty();
        }
        int start = -1;
        for (int i = 0; i < memberships.size(); i++) {
            if (!memberships.get(i).getEndDate().isBefore(today)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            Membership last = memberships.getLast();
            return Optional.of(new AccessWindow(last, last, last.getStartDate(), last.getEndDate(), false));
        }
        Membership first = memberships.get(start);
        Membership last = first;
        LocalDate validTo = first.getEndDate();
        boolean usable = usable(first, today);
        for (int i = start + 1; usable && i < memberships.size(); i++) {
            Membership next = memberships.get(i);
            if (!usable(next, today) || next.getStartDate().isAfter(validTo.plusDays(1))) {
                break;
            }
            if (next.getEndDate().isAfter(validTo)) {
                validTo = next.getEndDate();
                last = next;
            }
        }
        boolean started = !first.getStartDate().isAfter(today);
        boolean enabled = member.getStatus() == MemberStatus.ACTIVE && usable
                && (started || properties.isDevicesEnforceValidityDates());
        return Optional.of(new AccessWindow(first, last, first.getStartDate(), validTo, enabled));
    }

    /** Neither frozen, expired nor cancelled. Payment is not part of this decision. */
    static boolean usable(Membership membership, LocalDate today) {
        MembershipStatus effective = membership.effectiveStatus(today);
        return effective == MembershipStatus.ACTIVE || effective == MembershipStatus.PENDING;
    }

    @Transactional(readOnly = true)
    public boolean desiredEnabled(Member member) {
        return window(member).map(AccessWindow::enabled).orElse(false);
    }

    /**
     * Sends the window to the member's devices only if it differs from what was last sent, stamping
     * {@code accessChangedAt} so gateways take it over older device edits. Used after membership and
     * payment changes and by the hourly access check. Returns true when something was sent.
     */
    @Transactional
    public boolean refresh(Member member) {
        Optional<AccessWindow> window = window(member);
        if (sameAsSent(member, window)) {
            return false;
        }
        member.setAccessChangedAt(SyncClock.now());
        send(member, window);
        return true;
    }

    @Transactional
    public boolean refresh(Long memberId) {
        return memberRepository.findById(memberId).map(this::refresh).orElse(false);
    }

    /** Sends the current window to every device of the member (e.g. after activate/deactivate or a refused device edit). */
    @Transactional
    public void syncMember(Member member) {
        send(member, window(member));
    }

    /** A name change publishes the current desired user on each reader that already has this member. */
    @Transactional
    public void publishProfile(Member member) {
        desiredState.getObject().publishAccess(member);
    }

    private void send(Member member, Optional<AccessWindow> window) {
        member.setDeviceWindow(
                window.map(AccessWindow::validFrom).orElse(null),
                window.map(AccessWindow::validTo).orElse(null),
                window.map(AccessWindow::enabled).orElse(null));
        memberRepository.save(member);
        desiredState.getObject().publishAccess(member);
    }

    private static boolean sameAsSent(Member member, Optional<AccessWindow> window) {
        if (window.isEmpty()) {
            return member.getDeviceValidFrom() == null && member.getDeviceEnabled() == null;
        }
        AccessWindow w = window.get();
        return w.validFrom().equals(member.getDeviceValidFrom())
                && w.validTo().equals(member.getDeviceValidTo())
                && Objects.equals(w.enabled(), member.getDeviceEnabled());
    }
}
