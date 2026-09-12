package com.example.gym.notification;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_template")
public class NotificationTemplate extends TenantAwareEntity {

    @Column(name = "template_key", nullable = false, length = 64)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    protected NotificationTemplate() {
    }

    public NotificationTemplate(Long tenantId, String templateKey, NotificationChannel channel,
                                String subject, String body) {
        setTenantId(tenantId);
        this.templateKey = templateKey;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public void setTemplateKey(String templateKey) {
        this.templateKey = templateKey;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
