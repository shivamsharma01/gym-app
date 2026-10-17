package com.example.gym.member.dto;

import com.example.gym.member.Member;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MemberResponse(
        String id,
        String memberCode,
        String serialNumber,
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
        String deviceAuthority,
        Instant createdAt,
        String coverageStatus,
        /** Per-reader device user id; null deviceUserId when not mapped on that reader. */
        List<ReaderUserId> readers) {

    public record ReaderUserId(String deviceId, String deviceName, String deviceUserId) {
    }

    public static MemberResponse from(Member m, String coverageStatus, List<ReaderUserId> readers) {
        return new MemberResponse(
                m.getPublicId(),
                m.getMemberCode(),
                m.getSerialNumber(),
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
                m.getDeviceAuthority() == null ? "USER" : m.getDeviceAuthority().name(),
                m.getCreatedAt(),
                coverageStatus,
                readers == null ? List.of() : List.copyOf(readers));
    }
}
