package com.example.gym.notification;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "outbound_notification")
public class OutboundNotification extends TenantAwareEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "template_key", length = 64)
    private String templateKey;

    @Column(name = "recipient", nullable = false, length = 200)
    private String recipient;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.QUEUED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "member_id")
    private Long memberId;

    protected OutboundNotification() {
    }

    public OutboundNotification(Long tenantId, NotificationChannel channel, String templateKey,
                                String recipient, String subject, String body, Long memberId) {
        setTenantId(tenantId);
        this.channel = channel;
        this.templateKey = templateKey;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.memberId = memberId;
        this.status = NotificationStatus.QUEUED;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.attemptCount += 1;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = NotificationStatus.FAILED;
        this.attemptCount += 1;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}
