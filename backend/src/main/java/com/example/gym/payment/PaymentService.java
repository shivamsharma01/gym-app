package com.example.gym.payment;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.DeviceAuthorizationService;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipPaymentStatus;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.membership.MembershipService;
import com.example.gym.payment.dto.PaymentRequests.RecordPayment;
import com.example.gym.payment.dto.PaymentSummaryResponse;
import com.example.gym.security.AppUserPrincipal;
import com.example.gym.security.SecurityUtils;
import com.example.gym.tenant.TenantGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MembershipRepository membershipRepository;
    private final MemberService memberService;
    private final MembershipService membershipService;
    private final DeviceAuthorizationService deviceAuthorizationService;
    private final AuditService auditService;

    public PaymentService(PaymentRepository paymentRepository,
                          MembershipRepository membershipRepository,
                          MemberService memberService,
                          MembershipService membershipService,
                          DeviceAuthorizationService deviceAuthorizationService,
                          AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.membershipRepository = membershipRepository;
        this.memberService = memberService;
        this.membershipService = membershipService;
        this.deviceAuthorizationService = deviceAuthorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(Long tenantId, Pageable pageable) {
        return paymentRepository.findByTenantIdOrderByPaidOnDescIdDesc(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Payment> listForMember(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        return paymentRepository.findByMemberIdOrderByPaidOnDescIdDesc(member.getId());
    }

    @Transactional
    public Payment record(RecordPayment request, Long tenantId) {
        Member member = memberService.getByPublicId(request.memberId(), tenantId);

        Membership membership = null;
        if (StringUtils.hasText(request.membershipId())) {
            membership = membershipService.getByPublicId(request.membershipId(), tenantId);
            if (!membership.getMemberId().equals(member.getId())) {
                throw CommonExceptions.badRequest("Membership does not belong to the given member");
            }
        }

        String currency = resolveCurrency(request.currency(), membership);
        LocalDate paidOn = request.paidOn() != null ? request.paidOn() : LocalDate.now();

        Payment payment = new Payment(tenantId, member.getId(),
                membership == null ? null : membership.getId(),
                request.amount(), currency, request.method(), PaymentStatus.COMPLETED, paidOn);
        payment.setReference(request.reference());
        payment.setNotes(request.notes());
        applyReceivedBy(payment);
        Payment saved = paymentRepository.save(payment);

        if (membership != null) {
            recomputeMembershipPaymentStatus(membership);
        }

        auditService.record(AuditActions.PAYMENT_RECORDED, AuditActions.RESULT_SUCCESS,
                "Payment", saved.getPublicId(),
                Map.of("memberId", member.getPublicId(),
                        "amount", saved.getAmount().toPlainString(),
                        "currency", currency));
        return saved;
    }

    @Transactional
    public Payment refund(String paymentPublicId, Long tenantId) {
        Payment payment = paymentRepository.findByPublicId(paymentPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Payment"));
        TenantGuard.check(payment.getTenantId(), tenantId, "Payment");
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw CommonExceptions.badRequest("Payment is already refunded");
        }
        payment.setStatus(PaymentStatus.REFUNDED);
        Payment saved = paymentRepository.save(payment);

        if (payment.getMembershipId() != null) {
            membershipRepository.findById(payment.getMembershipId())
                    .ifPresent(this::recomputeMembershipPaymentStatus);
        }

        auditService.record(AuditActions.PAYMENT_REFUNDED, AuditActions.RESULT_SUCCESS,
                "Payment", saved.getPublicId(), null);
        return saved;
    }

    /** Recomputes a membership's amountPaid + payment status from its COMPLETED payments. */
    private void recomputeMembershipPaymentStatus(Membership membership) {
        BigDecimal paid = paymentRepository
                .findByMembershipIdAndStatus(membership.getId(), PaymentStatus.COMPLETED).stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        membership.setAmountPaid(paid);
        if (paid.signum() <= 0) {
            membership.setPaymentStatus(MembershipPaymentStatus.UNPAID);
        } else if (paid.compareTo(membership.getNetAmount()) >= 0) {
            membership.setPaymentStatus(MembershipPaymentStatus.PAID);
        } else {
            membership.setPaymentStatus(MembershipPaymentStatus.PARTIAL);
        }
        membershipRepository.save(membership);
        deviceAuthorizationService.syncMembership(membership);
    }

    private String resolveCurrency(String requested, Membership membership) {
        if (StringUtils.hasText(requested)) {
            if (!requested.matches("[A-Z]{3}")) {
                throw CommonExceptions.badRequest("currency must be a 3-letter ISO code");
            }
            return requested;
        }
        if (membership != null) {
            return membership.getCurrency();
        }
        throw CommonExceptions.badRequest("currency is required when no membership is specified");
    }

    private void applyReceivedBy(Payment payment) {
        AppUserPrincipal principal = SecurityUtils.currentPrincipal();
        payment.setReceivedByUserId(principal.getUserId());
        payment.setReceivedByUsername(principal.getUsername());
    }

    @Transactional(readOnly = true)
    public PaymentSummaryResponse summary(
            Long tenantId,
            LocalDate from,
            LocalDate to) {

        if (from.isAfter(to)) {
            throw CommonExceptions.badRequest("from date cannot be after to date");
        }

        BigDecimal total = paymentRepository.sumCompletedBetween(
                tenantId,
                from,
                to
        );

        long count = paymentRepository.countByTenantIdAndStatusAndPaidOnBetween(
                tenantId,
                PaymentStatus.COMPLETED,
                from,
                to
        );

        return new PaymentSummaryResponse(
                total == null ? BigDecimal.ZERO : total,
                count
        );
    }
}
