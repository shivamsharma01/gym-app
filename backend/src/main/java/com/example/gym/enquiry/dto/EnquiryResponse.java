package com.example.gym.enquiry.dto;

import com.example.gym.enquiry.Enquiry;
import java.time.Instant;

public record EnquiryResponse(
        String id,
        String name,
        String email,
        String phone,
        String message,
        String planInterest,
        String status,
        String staffNotes,
        Instant createdAt) {

    public static EnquiryResponse from(Enquiry e) {
        return new EnquiryResponse(
                e.getPublicId(),
                e.getName(),
                e.getEmail(),
                e.getPhone(),
                e.getMessage(),
                e.getPlanInterest(),
                e.getStatus().name(),
                e.getStaffNotes(),
                e.getCreatedAt());
    }
}
