package com.example.gym.payment;

import com.example.gym.common.web.PageResponse;
import com.example.gym.payment.dto.PaymentRequests.RecordPayment;
import com.example.gym.payment.dto.PaymentResponse;
import com.example.gym.payment.dto.PaymentSummaryResponse;
import com.example.gym.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payments")
public class PaymentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @Operation(summary = "List payments (most recent first)")
    public PageResponse<PaymentResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return PageResponse.from(
                paymentService.list(SecurityUtils.currentTenantId(), pageable), PaymentResponse::from);
    }

    @GetMapping("/members/{memberId}/payments")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @Operation(summary = "List a member's payments")
    public List<PaymentResponse> listForMember(@PathVariable String memberId) {
        return paymentService.listForMember(memberId, SecurityUtils.currentTenantId()).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_CREATE')")
    @Operation(summary = "Record a payment")
    public PaymentResponse record(@Valid @RequestBody RecordPayment request) {
        return PaymentResponse.from(paymentService.record(request, SecurityUtils.currentTenantId()));
    }

    @PostMapping("/payments/{id}/refund")
    @PreAuthorize("hasAuthority('PAYMENT_CREATE')")
    @Operation(summary = "Refund a payment")
    public PaymentResponse refund(@PathVariable String id) {
        return PaymentResponse.from(paymentService.refund(id, SecurityUtils.currentTenantId()));
    }

    @GetMapping("/payments/summary")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    @Operation(summary = "Payment summary for a date range")
    public PaymentSummaryResponse summary(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {

        return paymentService.summary(
                SecurityUtils.currentTenantId(),
                from,
                to
        );
    }
}
