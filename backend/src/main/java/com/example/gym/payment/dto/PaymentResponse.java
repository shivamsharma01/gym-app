package com.example.gym.payment.dto;

import com.example.gym.payment.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PaymentResponse(
        String id,
        BigDecimal amount,
        String currency,
        String method,
        String status,
        String reference,
        LocalDate paidOn,
        String receivedBy,
        String notes,
        Instant createdAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getPublicId(),
                p.getAmount(),
                p.getCurrency(),
                p.getMethod().name(),
                p.getStatus().name(),
                p.getReference(),
                p.getPaidOn(),
                p.getReceivedByUsername(),
                p.getNotes(),
                p.getCreatedAt());
    }
}
