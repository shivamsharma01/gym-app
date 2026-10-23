package com.example.gym.device.dto;

import com.example.gym.device.ExpectedOccupant;
import com.example.gym.device.ScanStamp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
            String failCode,
            @Size(max = ScanStamp.MAX_EPOCH) String scanEpoch,
            @PositiveOrZero Long scanSeq) {
    }

    public record ReportOccupied(
            @NotBlank String deviceId,
            @NotNull @Positive Long revision,
            @NotBlank String deviceUserId) {
    }

    /**
     * The gateway found someone other than the expected record at the id, or no one, and wrote
     * nothing. {@code present} false means the reader has no user there. Dates are reader-local
     * and an empty photo hash means no photo.
     */
    public record ReportOccupant(
            @NotBlank String deviceId,
            @NotNull @Positive Long revision,
            @NotBlank String deviceUserId,
            @NotNull Boolean present,
            @Size(max = 127) String name,
            @Size(max = 127) String nameEx,
            Integer userStatus,
            @Size(max = 40) String validFrom,
            @Size(max = 40) String validTo,
            @Size(max = 32) String authority,
            Integer doorNum,
            Integer timeSectionNum,
            @Size(max = 64) String faceSha256,
            @Size(max = ScanStamp.MAX_EPOCH) String scanEpoch,
            @PositiveOrZero Long scanSeq) {
    }

    /** The message ids of one reader observation the gateway sent before it applies desired state. */
    public record ObservationQuery(
            @NotBlank String deviceId,
            @NotNull @Size(min = 1, max = 5000) java.util.List<@NotBlank @Size(max = 64) String> messageIds) {
    }

    /**
     * {@code processed} is true only when every message is saved. {@code saved} and {@code waiting}
     * are how many of the asked messages are already saved and how many are still missing, so a
     * pause can be read without guessing.
     */
    public record ObservationStatus(boolean processed, int saved, int waiting) {
    }

    /**
     * A revision the gateway could not apply after repeated attempts ({@code permanent} false), or
     * cannot apply as sent ({@code permanent} true). The gateway keeps the revision until it applies
     * or is replaced.
     */
    public record ReportFailed(
            @NotBlank String deviceId,
            @NotNull @Positive Long revision,
            @Size(max = 64) String deviceUserId,
            @NotBlank String error,
            @NotNull @Positive Integer attempts,
            @NotNull Boolean permanent) {
    }

    /** {@code expected} is what the reader must still show at the id before it is written or removed. */
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
            boolean present,
            boolean facePresent,
            ExpectedOccupant expected) {
    }

    public record DesiredPage(long desiredRevision, long appliedRevision, java.util.List<DesiredItem> items) {
    }

    public record RevisionNotice(String deviceId, long revision) {
    }
}
