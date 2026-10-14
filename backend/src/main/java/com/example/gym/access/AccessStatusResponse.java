package com.example.gym.access;

import java.time.LocalDate;

/**
 * The computed business access decision for a member. This is the app's source of truth for
 * "should this member be allowed in". Whether each reader holds the member is reported by the
 * member's reader status, not here.
 */
public record AccessStatusResponse(
        String memberId,
        boolean allowed,
        String reason,
        String membershipId,
        String membershipStatus,
        LocalDate validUntil,
        String paymentStatus) {
}
