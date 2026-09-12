package com.example.gym.platform.dto;

public record TenantSummary(
        String id,
        String name,
        String slug,
        String status,
        String displayName) {
}
