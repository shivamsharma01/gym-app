package com.example.gym.notification.dto;

import java.time.Instant;

import com.example.gym.notification.annoucement.entity.Announcement;

public record AnnouncementView(
        String id,
        String title,
        String body,
        boolean published,
        Instant createdAt) {

    public static AnnouncementView from(Announcement a) {
        return new AnnouncementView(a.getPublicId(), a.getTitle(), a.getBody(), a.isPublished(),
                a.getCreatedAt());
    }
}
