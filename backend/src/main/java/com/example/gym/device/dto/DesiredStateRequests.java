package com.example.gym.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Gateway pull and acknowledgement for one reader's desired member. No public id is carried. */
public final class DesiredStateRequests {

    private DesiredStateRequests() {
    }

    public record AcknowledgeRevision(
            @NotBlank String deviceId,
            @NotNull @Positive Long revision,
            @NotBlank String deviceUserId,
            String name,
            String nameEx,
            Integer userStatus,
            String validFrom,
            String validTo,
            @Size(max = 64) String faceSha256,
            Boolean present,
            String failCode) {
    }

    public record ReportOccupied(
            @NotBlank String deviceId,
            @NotNull @Positive Long revision,
            @NotBlank String deviceUserId) {
    }

    public record DesiredItem(
            long revision,
            String deviceUserId,
            String name,
            String nameEx,
            int userStatus,
            String validFrom,
            String validTo,
            String authority,
            int doorNum,
            int timeSectionNum,
            String faceSha256,
            String faceBase64,
            boolean present) {
    }

    public record DesiredPage(long desiredRevision, long appliedRevision, java.util.List<DesiredItem> items) {
    }

    public record RevisionNotice(String deviceId, long revision) {
    }
}
