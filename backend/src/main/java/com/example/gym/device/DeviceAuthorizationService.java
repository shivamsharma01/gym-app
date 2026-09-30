package com.example.gym.device;

import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipPaymentStatus;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipStatus;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides what a member's devices hold — one validity window (from / to) plus enabled or
 * disabled — and enqueues the commands. Does not talk to hardware.
 * <p>
 * A device holds a single window, so it is taken from the membership that has not ended yet: the
 * one running today, else the next one. Memberships that run back to back (or overlap) and are
 * both usable are joined into one window. The device refuses entry outside the window by itself,
 * so gaps between memberships are closed without any command. Enabled means: member active, the
 * membership paid (partly paid counts) and not frozen, and either started or the devices enforce
 * the start date themselves. When the last membership has ended the device is disabled and keeps
 * its dates.
 */
@Service
public class DeviceAuthorizationService {

    /** What the devices should hold for a member. {@code first}/{@code last} are the memberships joined into it. */
    public record AccessWindow(Membership first, Membership last, LocalDate validFrom, LocalDate validTo,
                               boolean enabled) {
    }

    private final MemberDeviceMappingRepository mappingRepository;
    private final DeviceSyncService deviceSyncService;
    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;
    private final GatewayProperties properties;

    public DeviceAuthorizationService(MemberDeviceMappingRepository mappingRepository,
                                      DeviceSyncService deviceSyncService,
                                      MemberRepository memberRepository,
                                      MembershipRepository membershipRepository,
                                      GatewayProperties properties) {
        this.mappingRepository = mappingRepository;
        this.deviceSyncService = deviceSyncService;
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
        this.properties = properties;
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

    /** Paid (fully or partly) and neither frozen, expired nor cancelled. */
    static boolean usable(Membership membership, LocalDate today) {
        MembershipStatus effective = membership.effectiveStatus(today);
        return (effective == MembershipStatus.ACTIVE || effective == MembershipStatus.PENDING)
                && membership.getPaymentStatus() != MembershipPaymentStatus.UNPAID;
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

    /** Sends the current window to one device (new device user, repair). */
    @Transactional
    public void enqueueFor(Member member, Long deviceId, String deviceUserId) {
        enqueue(member, deviceId, deviceUserId, window(member));
    }

    /** The command payload for the current window, e.g. to compare with what a device reports. */
    public static Map<String, Object> windowPayload(String deviceUserId, AccessWindow window) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceUserId", deviceUserId);
        payload.put("enabled", window.enabled());
        payload.put("validFrom", window.validFrom().toString());
        payload.put("validTo", window.validTo().toString());
        return payload;
    }

    static Map<String, Object> disablePayload(String deviceUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceUserId", deviceUserId);
        payload.put("enabled", false);
        return payload;
    }

    private void send(Member member, Optional<AccessWindow> window) {
        member.setDeviceWindow(
                window.map(AccessWindow::validFrom).orElse(null),
                window.map(AccessWindow::validTo).orElse(null),
                window.map(AccessWindow::enabled).orElse(null));
        memberRepository.save(member);
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            enqueue(member, mapping.getDeviceId(), mapping.getDeviceUserId(), window);
        }
    }

    private void enqueue(Member member, Long deviceId, String deviceUserId, Optional<AccessWindow> window) {
        if (window.isEmpty()) {
            deviceSyncService.enqueue(member.getTenantId(), deviceId, member.getId(), null,
                    SyncCommandType.DISABLE_USER, disablePayload(deviceUserId));
            return;
        }
        AccessWindow w = window.get();
        deviceSyncService.enqueue(member.getTenantId(), deviceId, member.getId(), w.first().getId(),
                w.enabled() ? SyncCommandType.UPDATE_VALIDITY : SyncCommandType.DISABLE_USER,
                windowPayload(deviceUserId, w));
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
