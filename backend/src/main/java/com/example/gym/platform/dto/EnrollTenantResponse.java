package com.example.gym.platform.dto;

public record EnrollTenantResponse(
        TenantSummary tenant,
        String ownerUsername,
        String ownerEmail) {
}
