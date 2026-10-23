package com.example.gym.member.dto;

import com.example.gym.member.Member;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
        String deviceAuthority,
        Instant createdAt,
        String coverageStatus,
        /**
         * Per-reader device user id and sync marker. The id is the mapping, or the id the reader
         * already shows for this photo when there is no mapping yet ({@code syncState=seen}).
         */
        List<ReaderUserId> readers) {

    /**
     * @param syncState {@code none}, {@code ok}, {@code pending}, {@code held}, {@code failed},
     *                  {@code absent}, or {@code seen} (already on the reader, not a published mapping)
     */
    public record ReaderUserId(String deviceId, String deviceName, String deviceUserId, String syncState) {
    }

    public static MemberResponse from(Member m, String coverageStatus, List<ReaderUserId> readers) {
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
                m.getDeviceAuthority() == null ? "USER" : m.getDeviceAuthority().name(),
                m.getCreatedAt(),
                coverageStatus,
                readers == null ? List.of() : List.copyOf(readers));
    }
}
