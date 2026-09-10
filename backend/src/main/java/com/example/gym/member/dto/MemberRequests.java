package com.example.gym.member.dto;

import com.example.gym.member.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Request payloads for members. */
public final class MemberRequests {

    private MemberRequests() {
    }

    public record CreateMember(
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Email @Size(max = 200) String email,
            @Size(max = 32) String phone,
            @Past LocalDate dateOfBirth,
            Gender gender,
            @Size(max = 1000) String notes,
            /** Optional; auto-generated when blank. */
            @Size(max = 32) String memberCode) {
    }

    public record UpdateMember(
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Email @Size(max = 200) String email,
            @Size(max = 32) String phone,
            @Past LocalDate dateOfBirth,
            Gender gender,
            @Size(max = 1000) String notes) {
    }
}
