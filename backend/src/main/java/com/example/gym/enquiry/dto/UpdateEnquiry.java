package com.example.gym.enquiry.dto;

import com.example.gym.enquiry.EnquiryStatus;
import jakarta.validation.constraints.Size;

public record UpdateEnquiry(
        EnquiryStatus status,
        @Size(max = 1000) String staffNotes) {
}
