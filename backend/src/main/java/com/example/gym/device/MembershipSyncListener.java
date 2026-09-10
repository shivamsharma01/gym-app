package com.example.gym.device;

import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipChangedEvent;
import com.example.gym.membership.MembershipRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns membership state changes into device authorization sync commands (§8). Runs synchronously
 * within the membership transaction, so the membership change and its outbox commands commit
 * together. No device I/O happens here — commands are queued and delivered by the sync engine.
 */
@Component
public class MembershipSyncListener {

    private final MembershipRepository membershipRepository;
    private final DeviceAuthorizationService authorizationService;

    public MembershipSyncListener(MembershipRepository membershipRepository,
                                  DeviceAuthorizationService authorizationService) {
        this.membershipRepository = membershipRepository;
        this.authorizationService = authorizationService;
    }

    @EventListener
    public void onMembershipChanged(MembershipChangedEvent event) {
        Membership membership = membershipRepository.findById(event.membershipId()).orElse(null);
        if (membership != null) {
            authorizationService.syncMembership(membership);
        }
    }
}
