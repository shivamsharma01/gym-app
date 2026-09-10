package com.example.gym.membership;

/**
 * Device authorization state, modelled separately from business {@link MembershipStatus} (§8 of the
 * spec). Phase 2 never contacts a device, so memberships are created {@code NOT_SYNCED}; the device
 * sync engine (Phase 3) drives transitions and must never fabricate a {@code SYNCED} result.
 */
public enum DeviceSyncState {
    NOT_SYNCED,
    PENDING,
    SYNCED,
    FAILED,
    OFFLINE
}
