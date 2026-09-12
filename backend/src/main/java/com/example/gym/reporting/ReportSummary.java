package com.example.gym.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ReportSummary(
        long membersTotal,
        long membersActive,
        long membershipsActive,
        long membershipsExpiringSoon,
        long membershipsExpired,
        BigDecimal paymentsCompletedAmount,
        long paymentsCompletedCount,
        long attendanceEvents,
        long accessDenied,
        List<Map<String, Object>> devices) {
}
