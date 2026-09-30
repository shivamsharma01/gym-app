package com.example.gym.membership.dto;

import java.time.LocalDate;

public record PlanActiveMemberResponse(
        String memberId,
        String memberCode,
        String fullName,
        String phone,
        LocalDate startDate,
        LocalDate endDate,
        boolean expiringWithin7Days) {
}
