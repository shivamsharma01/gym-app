package com.example.gym.member.dto;

import com.example.gym.member.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Request payloads for members. */
public final class MemberRequests {

    /** Readers store the id as text; blank means "not provided". */
    static final String SERIAL_PATTERN = "^$|^\\s*[A-Za-z0-9_-]+\\s*$";
    static final String SERIAL_MESSAGE = "Serial number may contain only letters, digits, '-' and '_'";

    private MemberRequests() {
    }

    public record CreateMember(
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Email @Size(max = 200) String email,
            @Pattern(regexp = "\\d{10}", message = "Phone must be exactly 10 digits")
            @Size(max = 10) String phone,
            @Past LocalDate dateOfBirth,
            Gender gender,
            @Size(max = 1000) String notes,
            /** Optional; auto-generated when blank. */
            @Size(max = 32) String memberCode,
            String deviceAuthority,
            /** Optional; the next free number when blank. Used as the device user id. */
            @Size(max = 31) @Pattern(regexp = SERIAL_PATTERN, message = SERIAL_MESSAGE) String serialNumber) {
    }

    public record UpdateMember(
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @Email @Size(max = 200) String email,
            @Pattern(regexp = "\\d{10}", message = "Phone must be exactly 10 digits")
            @Size(max = 10) String phone,
            @Past LocalDate dateOfBirth,
            Gender gender,
            @Size(max = 1000) String notes,
            String deviceAuthority,
            /** Optional; unchanged when blank. A new value moves the member on every reader. */
            @Size(max = 31) @Pattern(regexp = SERIAL_PATTERN, message = SERIAL_MESSAGE) String serialNumber) {
    }

    public record UpdateDeviceAuthority(
            @NotBlank String authority) {
    }
}
