package com.example.gym.audit;

import java.time.Instant;

public record AuditLogResponse(
        String id,
        String action,
        String result,
        String resourceType,
        String resourceId,
        String actorUsername,
        String ipAddress,
        String correlationId,
        String details,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getPublicId(),
                log.getAction(),
                log.getResult(),
                log.getResourceType(),
                log.getResourceId(),
                log.getActorUsername(),
                log.getIpAddress(),
                log.getCorrelationId(),
                log.getDetails(),
                log.getCreatedAt());
    }
}
