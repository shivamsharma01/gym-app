package com.example.gym.device.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The pending people and members staff confirmed from a reader's match preview. */
public record LinkMatchesRequest(@NotNull @Size(max = 5000) List<@Valid Pair> links) {

    public record Pair(@NotBlank String enrollmentId, @NotBlank String memberId) {
    }
}
