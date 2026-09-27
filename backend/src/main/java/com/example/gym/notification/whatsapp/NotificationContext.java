package com.example.gym.notification.whatsapp;

import java.time.LocalDate;

import com.example.gym.member.Member;
import com.example.gym.membership.Membership;

public record NotificationContext(
        Member member,
        Membership membership,
        String gymName,
        Integer daysRemaining) {

    public LocalDate expiryDate() {
        if (membership == null) {
            return null;
        }

        return membership.getEndDate();
    }
}
