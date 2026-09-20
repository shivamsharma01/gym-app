package com.example.gym.payment.dto;

import java.math.BigDecimal;

public record PaymentSummaryResponse(
        BigDecimal totalAmount,
        long paymentCount
) {
}