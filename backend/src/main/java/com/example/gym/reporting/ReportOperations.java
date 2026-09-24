package com.example.gym.reporting;

import java.math.BigDecimal;
import java.util.List;

public record ReportOperations(
        Overview overview,
        List<CollectionByMethod> collectionsByMethod,
        List<PaymentRow> payments,
        List<MembershipRow> expiringIn7Days,
        List<MembershipRow> expiringMemberships,
        List<DueRow> outstandingDues,
        List<MemberRow> newMembers,
        List<AttendanceRow> attendance,
        List<MemberRow> inactiveMembers) {

    public record Overview(
            long membersTotal,
            long activeMembers,
            long newMembers,
            long activeMemberships,
            long expiringIn7Days,
            long expiringIn30Days,
            long expiredMemberships,
            long outstandingCount,
            BigDecimal outstandingAmount,
            long collectionCount,
            BigDecimal collectionAmount,
            long attendanceCount,
            long uniqueAttendees,
            long accessDenied,
            long renewals) {}

    public record CollectionByMethod(String method, long count, BigDecimal amount) {}

    public record PaymentRow(
            String id, String memberName, String memberCode, String paidOn,
            BigDecimal amount, String currency, String method, String status) {}

    public record MembershipRow(
            String id, String memberName, String memberCode, String planName,
            String status, String startDate, String endDate, BigDecimal price,
            BigDecimal amountPaid, BigDecimal balance) {}

    public record DueRow(
            String id, String memberName, String memberCode, String planName,
            String status, String endDate, BigDecimal planAmount,
            BigDecimal amountPaid, BigDecimal balance) {}

    public record MemberRow(
            String id, String memberCode, String name, String phone, String joinedOn) {}

    public record AttendanceRow(
            String memberId, String memberCode, String memberName,
            long visits, String lastVisit) {}
}
