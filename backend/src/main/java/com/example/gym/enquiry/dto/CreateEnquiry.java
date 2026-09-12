package com.example.gym.enquiry.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEnquiry(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Email @Size(max = 200) String email,
        @Size(max = 32) String phone,
        @NotBlank @Size(max = 2000) String message,
        @Size(max = 120) String planInterest) {
}
