package com.example.gym.notification;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "announcement")
public class Announcement extends TenantAwareEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 4000)
    private String body;

    @Column(name = "published", nullable = false)
    private boolean published;

    protected Announcement() {
    }

    public Announcement(Long tenantId, String title, String body, boolean published) {
        setTenantId(tenantId);
        this.title = title;
        this.body = body;
        this.published = published;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }
}
