package com.example.gym.membership.dto;

import com.example.gym.membership.Membership;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MembershipResponse(
        String id,
        String planName,
        BigDecimal price,
        String currency,
        LocalDate startDate,
        LocalDate endDate,
        boolean endDateInferred,
        String status,
        String effectiveStatus,
        String paymentStatus,
        BigDecimal amountPaid,
        String deviceSyncState,
        LocalDate cancelledOn,
        String cancelReason,
        Instant createdAt) {

    public static MembershipResponse from(Membership m, LocalDate today) {
        return new MembershipResponse(
                m.getPublicId(),
                m.getPlanName(),
                m.getPrice(),
                m.getCurrency(),
                m.getStartDate(),
                m.getEndDate(),
                m.isEndDateInferred(),
                m.getStatus().name(),
                m.effectiveStatus(today).name(),
                m.getPaymentStatus().name(),
                m.getAmountPaid(),
                m.getDeviceSyncState().name(),
                m.getCancelledOn(),
                m.getCancelReason(),
                m.getCreatedAt());
    }
}
