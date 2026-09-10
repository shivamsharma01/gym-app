package com.example.gym.access;

import java.time.LocalDate;

/**
 * The computed business access decision for a member. This is the app's source of truth for
 * "should this member be allowed in". Device authorization state is reported separately
 * ({@code deviceSyncState}) and is driven by the Phase 3 sync engine.
 */
public record AccessStatusResponse(
        String memberId,
        boolean allowed,
        String reason,
        String membershipId,
        String membershipStatus,
        LocalDate validUntil,
        String paymentStatus,
        String deviceSyncState) {
}
