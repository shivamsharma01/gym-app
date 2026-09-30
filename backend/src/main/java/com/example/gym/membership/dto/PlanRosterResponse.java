package com.example.gym.membership.dto;

import com.example.gym.plan.MembershipPlan;
import java.math.BigDecimal;

public record PlanRosterResponse(
        String id,
        String name,
        String description,
        BigDecimal price,
        String currency,
        int durationDays,
        String status,
        long activeMembers,
        long expiringWithin7Days) {

    public static PlanRosterResponse from(MembershipPlan plan, long activeMembers, long expiringWithin7Days) {
        return new PlanRosterResponse(
                plan.getPublicId(),
                plan.getName(),
                plan.getDescription(),
                plan.getPrice(),
                plan.getCurrency(),
                plan.getDurationDays(),
                plan.getStatus().name(),
                activeMembers,
                expiringWithin7Days);
    }
}
