package com.example.gym.device;

import com.example.gym.member.MemberRepository;
import com.example.gym.membership.MembershipChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns membership changes into device access updates (§8). Runs synchronously within the
 * membership transaction, so the change and its outbox commands commit together. Only changes
 * that alter what the devices hold are sent (e.g. adding a future membership does not touch the
 * devices until its turn comes); those stamp the member's {@code accessChangedAt}.
 */
@Component
public class MembershipSyncListener {

    private final MemberRepository memberRepository;
    private final DeviceAuthorizationService authorizationService;

    public MembershipSyncListener(MemberRepository memberRepository,
                                  DeviceAuthorizationService authorizationService) {
        this.memberRepository = memberRepository;
        this.authorizationService = authorizationService;
    }

    @EventListener
    public void onMembershipChanged(MembershipChangedEvent event) {
        memberRepository.findById(event.memberId()).ifPresent(authorizationService::refresh);
    }
}
