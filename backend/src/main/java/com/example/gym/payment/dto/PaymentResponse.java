package com.example.gym.payment.dto;

import com.example.gym.payment.Payment;
import com.example.gym.member.Member;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PaymentResponse(
        String id,
        String memberId,
        String memberName,
        BigDecimal amount,
        String currency,
        String method,
        String status,
        String reference,
        LocalDate paidOn,
        String receivedBy,
        String notes,
        Instant createdAt) {

    public static PaymentResponse from(Payment p, Member member) {
        return new PaymentResponse(
                p.getPublicId(),
                member.getMemberCode(),
                member.getFullName(),
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
