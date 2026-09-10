package com.example.gym.plan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request payloads for membership plans. */
public final class PlanRequests {

    private PlanRequests() {
    }

    public record CreatePlan(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code")
            String currency,
            @Min(1) int durationDays) {
    }

    public record UpdatePlan(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(1) int durationDays) {
    }
}
