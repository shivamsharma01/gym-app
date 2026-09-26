package com.example.gym.payment;

public record PaymentChangedEvent(Long tenantId, Long memberId, Long paymentId, PaymentEventType type) {
}
