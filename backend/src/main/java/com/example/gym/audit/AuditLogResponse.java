package com.example.gym.audit;

import java.time.Instant;

public record AuditLogResponse(
        String id,
        String action,
        String result,
        String resourceType,
        String resourceId,
        String resourceLabel,
        String actorUsername,
        String ipAddress,
        String correlationId,
        String details,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog log, String resourceLabel) {
        return new AuditLogResponse(
                log.getPublicId(),
                log.getAction(),
                log.getResult(),
                log.getResourceType(),
                log.getResourceId(),
                resourceLabel,
                log.getActorUsername(),
                log.getIpAddress(),
                log.getCorrelationId(),
                log.getDetails(),
                log.getCreatedAt());
    }

    /** @deprecated Prefer {@link #from(AuditLog, String)} with a resolved label. */
    public static AuditLogResponse from(AuditLog log) {
        return from(log, null);
    }
}
