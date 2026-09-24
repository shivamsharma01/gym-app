package com.example.gym.reporting;

import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
@PreAuthorize("hasAuthority('REPORT_VIEW')")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Aggregated membership, payment, attendance and device stats")
    public ReportSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.summary(SecurityUtils.currentTenantId(), from, to);
    }

    @GetMapping("/operations")
    @Operation(summary = "Day-to-day gym owner reports")
    public ReportOperations operations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.operations(SecurityUtils.currentTenantId(), from, to);
    }

    @GetMapping("/memberships")
    @Operation(summary = "Membership snapshot (server-side list, not raw event dump)")
    public List<Map<String, Object>> memberships() {
        return reportService.memberships(SecurityUtils.currentTenantId());
    }
}
