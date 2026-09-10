package com.example.gym.plan.dto;

import com.example.gym.plan.MembershipPlan;
import java.math.BigDecimal;
import java.time.Instant;

public record PlanResponse(
        String id,
        String name,
        String description,
        BigDecimal price,
        String currency,
        int durationDays,
        String status,
        Instant createdAt) {

    public static PlanResponse from(MembershipPlan plan) {
        return new PlanResponse(
                plan.getPublicId(),
                plan.getName(),
                plan.getDescription(),
                plan.getPrice(),
                plan.getCurrency(),
                plan.getDurationDays(),
                plan.getStatus().name(),
                plan.getCreatedAt());
    }
}
