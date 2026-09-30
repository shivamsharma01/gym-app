package com.example.gym.membership.dto;

/** Active-membership totals for one plan. {@code planId} is the internal plan id. */
public record PlanActivityCount(Long planId, Long activeMembers, Long expiringWithin7Days) {
}
