package com.example.gym.device.dto;

import jakarta.validation.constraints.NotBlank;

/** The existing member a pending enrollment should keep. The reader's device user id stays. */
public record LinkMemberRequest(@NotBlank String memberId) {
}
