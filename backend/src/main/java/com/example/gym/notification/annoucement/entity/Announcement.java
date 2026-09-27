package com.example.gym.notification.annoucement.entity;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "announcement")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Announcement
        extends TenantAwareEntity {

    @Column(
            name = "title",
            nullable = false,
            length = 200)
    private String title;

    @Column(
            name = "body",
            nullable = false,
            length = 4000)
    private String body;

    @Column(
            name = "published",
            nullable = false)
    private boolean published;

    public Announcement(
            Long tenantId,
            String title,
            String body,
            boolean published) {

        setTenantId(tenantId);
        this.title = title;
        this.body = body;
        this.published = published;
    }
}