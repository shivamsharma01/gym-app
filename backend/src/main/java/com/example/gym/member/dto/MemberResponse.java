package com.example.gym.member.dto;

import com.example.gym.member.Member;
import java.time.Instant;
import java.time.LocalDate;

public record MemberResponse(
        String id,
        String memberCode,
        String firstName,
        String lastName,
        String fullName,
        String email,
        String phone,
        LocalDate dateOfBirth,
        String gender,
        String status,
        LocalDate joinedOn,
        String notes,
        Instant createdAt) {

    public static MemberResponse from(Member m) {
        return new MemberResponse(
                m.getPublicId(),
                m.getMemberCode(),
                m.getFirstName(),
                m.getLastName(),
                m.getFullName(),
                m.getEmail(),
                m.getPhone(),
                m.getDateOfBirth(),
                m.getGender().name(),
                m.getStatus().name(),
                m.getJoinedOn(),
                m.getNotes(),
                m.getCreatedAt());
    }
}
