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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Translates a member's current business access decision into device authorization commands.
 * Does not talk to hardware — only enqueues outbox work.
 */
@Service
public class DeviceAuthorizationService {

    private final MemberDeviceMappingRepository mappingRepository;
    private final DeviceSyncService deviceSyncService;
    private final MemberRepository memberRepository;
    private final MembershipRepository membershipRepository;

    public DeviceAuthorizationService(MemberDeviceMappingRepository mappingRepository,
                                      DeviceSyncService deviceSyncService,
                                      MemberRepository memberRepository,
                                      MembershipRepository membershipRepository) {
        this.mappingRepository = mappingRepository;
        this.deviceSyncService = deviceSyncService;
        this.memberRepository = memberRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public void syncMembership(Membership membership) {
        List<MemberDeviceMapping> mappings = mappingRepository.findByMemberId(membership.getMemberId());
        if (mappings.isEmpty()) {
            return;
        }
        Member member = memberRepository.findById(membership.getMemberId()).orElse(null);
        boolean enabled = authorizationEnabled(membership, member);
        for (MemberDeviceMapping mapping : mappings) {
            enqueueForMapping(membership, mapping, enabled);
        }
    }

    /**
     * Re-push authorization for every mapped device after member activate/deactivate.
     * Inactive members always enqueue DISABLE_USER; re-enable uses UPDATE_VALIDITY.
     */
    @Transactional
    public void syncMember(Member member) {
        List<MemberDeviceMapping> mappings = mappingRepository.findByMemberId(member.getId());
        if (mappings.isEmpty()) {
            return;
        }
        List<Membership> memberships =
                membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId());
        if (memberships.isEmpty()) {
            if (member.getStatus() != MemberStatus.ACTIVE) {
                for (MemberDeviceMapping mapping : mappings) {
                    deviceSyncService.enqueue(member.getTenantId(), mapping.getDeviceId(),
                            member.getId(), null,
                            SyncCommandType.DISABLE_USER, disablePayload(mapping.getDeviceUserId()));
                }
            }
            return;
        }
        for (Membership membership : memberships) {
            boolean enabled = authorizationEnabled(membership, member);
            for (MemberDeviceMapping mapping : mappings) {
                enqueueForMapping(membership, mapping, enabled);
            }
        }
    }

    private void enqueueForMapping(Membership membership, MemberDeviceMapping mapping, boolean enabled) {
        if (enabled) {
            deviceSyncService.enqueue(membership.getTenantId(), mapping.getDeviceId(),
                    membership.getMemberId(), membership.getId(),
                    SyncCommandType.UPDATE_VALIDITY,
                    DeviceService.validityPayload(mapping.getDeviceUserId(), membership, true));
        } else {
            deviceSyncService.enqueue(membership.getTenantId(), mapping.getDeviceId(),
                    membership.getMemberId(), membership.getId(),
                    SyncCommandType.DISABLE_USER, disablePayload(mapping.getDeviceUserId()));
        }
    }

    static boolean authorizationEnabled(Membership membership) {
        return authorizationEnabled(membership, null);
    }

    static boolean authorizationEnabled(Membership membership, Member member) {
        if (member != null && member.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (membership.effectiveStatus(today) != MembershipStatus.ACTIVE) {
            return false;
        }
        return membership.getPaymentStatus() != MembershipPaymentStatus.UNPAID;
    }

    static Map<String, Object> disablePayload(String deviceUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceUserId", deviceUserId);
        payload.put("enabled", false);
        return payload;
    }
}
