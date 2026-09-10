package com.example.gym.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Request payloads for membership lifecycle operations. */
public final class MembershipRequests {

    private MembershipRequests() {
    }

    public record CreateMembership(
            @NotBlank String memberId,
            @NotBlank String planId,
            /** Optional; defaults to today. */
            LocalDate startDate) {
    }

    public record RenewMembership(
            /** Optional; reuse the current membership's plan when omitted. */
            String planId,
            /** Optional; defaults to the day after the current end date (or today if already past). */
            LocalDate startDate) {
    }

    public record CancelMembership(
            @Size(max = 300) String reason) {
    }
}
