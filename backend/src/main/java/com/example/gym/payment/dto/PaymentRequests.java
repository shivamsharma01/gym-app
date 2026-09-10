package com.example.gym.payment.dto;

import com.example.gym.payment.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request payloads for payments. */
public final class PaymentRequests {

    private PaymentRequests() {
    }

    public record RecordPayment(
            @NotBlank String memberId,
            /** Optional; link the payment to a specific membership. */
            String membershipId,
            @NotNull @DecimalMin(value = "0.0", inclusive = false)
            @Digits(integer = 10, fraction = 2) BigDecimal amount,
            /** Optional; defaults to the membership/tenant currency when omitted. */
            String currency,
            @NotNull PaymentMethod method,
            @Size(max = 120) String reference,
            /** Optional; defaults to today. */
            LocalDate paidOn,
            @Size(max = 500) String notes) {
    }
}
