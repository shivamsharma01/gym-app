package com.example.gym.notification.dto;

import com.example.gym.notification.OutboundNotification;
import java.time.Instant;

public record OutboundView(
        String id,
        String channel,
        String templateKey,
        String recipient,
        String subject,
        String status,
        int attemptCount,
        String lastError,
        Instant sentAt,
        Instant createdAt) {

    public static OutboundView from(OutboundNotification n) {
        return new OutboundView(n.getPublicId(), n.getChannel().name(), n.getTemplateKey(),
                n.getRecipient(), n.getSubject(), n.getStatus().name(), n.getAttemptCount(),
                n.getLastError(), n.getSentAt(), n.getCreatedAt());
    }
}
