package com.example.gym.notification.dto;

import com.example.gym.notification.NotificationTemplate;
import java.time.Instant;

public record TemplateView(
        String id,
        String templateKey,
        String channel,
        String subject,
        String body,
        Instant createdAt) {

    public static TemplateView from(NotificationTemplate t) {
        return new TemplateView(t.getPublicId(), t.getTemplateKey(), t.getChannel().name(),
                t.getSubject(), t.getBody(), t.getCreatedAt());
    }
}
