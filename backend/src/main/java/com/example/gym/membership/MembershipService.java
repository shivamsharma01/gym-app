package com.example.gym.membership;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.membership.dto.MembershipRequests.CreateMembership;
import com.example.gym.membership.dto.MembershipRequests.RenewMembership;
import com.example.gym.membership.dto.MembershipRequests.UpdateMembershipDates;
import com.example.gym.plan.MembershipPlan;
import com.example.gym.plan.PlanService;
import com.example.gym.plan.PlanStatus;
import com.example.gym.plan.MembershipPlanRepository;
import com.example.gym.tenant.TenantGuard;
import com.example.gym.membership.MembershipChangedEvent.ChangeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Membership lifecycle: create, renew (preserving history), freeze/unfreeze (pausing validity),
 * and cancel. Device I/O is not performed here — a {@link MembershipChangedEvent} is published
 * in the same transaction so the outbox can enqueue authorization commands. Results stay
 * {@code NOT_SYNCED}/{@code PENDING} until the gateway reports {@code SYNC_RESULT}.
 */
@Service
public class MembershipService {

    private final MembershipRepository membershipRepository;
    private final MembershipPlanRepository planRepository;
    private final MemberService memberService;
    private final PlanService planService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public MembershipService(MembershipRepository membershipRepository,
                             MembershipPlanRepository planRepository,
                             MemberService memberService,
                             PlanService planService,
                             AuditService auditService,
                             ApplicationEventPublisher eventPublisher) {
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
        this.memberService = memberService;
        this.planService = planService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public Membership getByPublicId(String publicId, Long tenantId) {
        Membership membership = membershipRepository.findByPublicIdAndDeletedFalse(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Membership"));
        TenantGuard.check(membership.getTenantId(), tenantId, "Membership");
        return membership;
    }

    @Transactional(readOnly = true)
    public List<Membership> listForMember(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        return membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId());
    }

    @Transactional
    public Membership create(CreateMembership request, Long tenantId) {
        Member member = memberService.getByPublicId(request.memberId(), tenantId);
        MembershipPlan plan = planService.requireActive(request.planId(), tenantId);
        LocalDate start = request.startDate() != null ? request.startDate() : LocalDate.now();
        LocalDate end = resolveEnd(start, request.endDate(), plan);
        Membership membership = build(tenantId, member.getId(), plan, start, end);
        Membership saved = membershipRepository.save(membership);

        auditService.record(AuditActions.MEMBERSHIP_CREATED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(),
                Map.of("memberId", member.getPublicId(), "plan", plan.getName()));
        publish(saved, ChangeType.CREATED);
        return saved;
    }

    @Transactional
    public Membership renew(
            String membershipPublicId,
            RenewMembership request,
            Long tenantId
    ) {
        Membership current =
                getByPublicId(membershipPublicId, tenantId);

        MembershipPlan plan =
                resolveRenewalPlan(
                        current,
                        Long.valueOf(request.planId()),
                        tenantId
                );

        LocalDate start = request.startDate();

        if (request.endDate().isBefore(start)) {
            throw CommonExceptions.badRequest(
                    "End date cannot be before start date"
            );
        }

        /*
         * Current plan credit is allowed only when:
         *
         * 1. Renewal starts on the current membership's
         *    original start date
         *
         * OR
         *
         * 2. Renewal starts exactly one day after the
         *    current membership ends.
         *
         * Any later start date means there is a gap,
         * so there is NO credit for the previous plan.
         */
        boolean qualifiesForCredit =
                start.equals(current.getStartDate())
                        || start.equals(
                        current.getEndDate().plusDays(1)
                );

        BigDecimal amountToCollect;

        if (qualifiesForCredit) {
            amountToCollect = plan.getPrice()
                    .subtract(current.getPrice())
                    .max(BigDecimal.ZERO);
        } else {
            amountToCollect = plan.getPrice();
        }

        Membership renewal = build(
                tenantId,
                current.getMemberId(),
                plan,
                start,
                request.endDate()
        );

        renewal.setAmountPaid(BigDecimal.ZERO);

        renewal.setPaymentStatus(
                amountToCollect.compareTo(BigDecimal.ZERO) == 0
                        ? MembershipPaymentStatus.PAID
                        : MembershipPaymentStatus.UNPAID
        );

        Membership saved =
                membershipRepository.save(renewal);

        auditService.record(
                AuditActions.MEMBERSHIP_RENEWED,
                AuditActions.RESULT_SUCCESS,
                "Membership",
                saved.getPublicId(),
                Map.of(
                        "renewedFrom", current.getPublicId(),
                        "plan", plan.getName()
                )
        );

        publish(saved, ChangeType.RENEWED);

        return saved;
    }

    private MembershipPlan resolveRenewalPlan(
            Membership current,
            Long requestedPlanId,
            Long tenantId
    ) {
        if (requestedPlanId == null) {
            if (current.getPlanId() == null) {
                throw CommonExceptions.badRequest(
                        "Current membership has no plan"
                );
            }

            return planRepository.findById(current.getPlanId())
                    .filter(plan -> plan.getTenantId().equals(tenantId))
                    .orElseThrow(() ->
                            CommonExceptions.notFound(
                                    "Membership plan not found"
                            )
                    );
        }

        return planRepository.findById(requestedPlanId)
                .filter(plan -> plan.getTenantId().equals(tenantId))
                .orElseThrow(() ->
                        CommonExceptions.notFound(
                                "Membership plan not found"
                        )
                );
    }

    @Transactional
    public Membership freeze(String membershipPublicId, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        LocalDate today = LocalDate.now();
        if (membership.effectiveStatus(today) != MembershipStatus.ACTIVE) {
            throw CommonExceptions.badRequest("Only an active membership can be frozen");
        }
        membership.setStatus(MembershipStatus.FROZEN);
        membership.setFrozenOn(today);
        Membership saved = membershipRepository.save(membership);
        auditService.record(AuditActions.MEMBERSHIP_FROZEN, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), null);
        publish(saved, ChangeType.FROZEN);
        return saved;
    }

    @Transactional
    public Membership unfreeze(String membershipPublicId, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        if (membership.getStatus() != MembershipStatus.FROZEN || membership.getFrozenOn() == null) {
            throw CommonExceptions.badRequest("Membership is not frozen");
        }
        LocalDate today = LocalDate.now();
        int frozenDays = (int) ChronoUnit.DAYS.between(membership.getFrozenOn(), today);
        if (frozenDays > 0) {
            // Extend validity by the frozen duration so members don't lose paid time.
            membership.setEndDate(membership.getEndDate().plusDays(frozenDays));
            membership.setFreezeDaysAccumulated(membership.getFreezeDaysAccumulated() + frozenDays);
        }
        membership.setFrozenOn(null);
        membership.setStatus(today.isAfter(membership.getEndDate())
                ? MembershipStatus.EXPIRED : MembershipStatus.ACTIVE);
        Membership saved = membershipRepository.save(membership);
        auditService.record(AuditActions.MEMBERSHIP_UNFROZEN, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), Map.of("extendedDays", frozenDays));
        publish(saved, ChangeType.UNFROZEN);
        return saved;
    }

    @Transactional
    public Membership cancel(String membershipPublicId, String reason, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        if (membership.getStatus() == MembershipStatus.CANCELLED) {
            throw CommonExceptions.badRequest("Membership is already cancelled");
        }
        membership.setStatus(MembershipStatus.CANCELLED);
        membership.setCancelledOn(LocalDate.now());
        membership.setCancelReason(StringUtils.hasText(reason) ? reason : null);
        Membership saved = membershipRepository.save(membership);
        auditService.record(AuditActions.MEMBERSHIP_CANCELLED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), reason == null ? null : Map.of("reason", reason));
        publish(saved, ChangeType.CANCELLED);
        return saved;
    }

    @Transactional
    public Membership updateDates(String membershipPublicId, UpdateMembershipDates request, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        if (membership.getStatus() == MembershipStatus.CANCELLED
                || membership.getStatus() == MembershipStatus.FROZEN) {
            throw CommonExceptions.badRequest("Cannot change dates on a frozen or cancelled membership");
        }
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        requireEndOnOrAfterStart(start, end);
        membership.setStartDate(start);
        membership.setEndDate(end);
        LocalDate today = LocalDate.now();
        if (start.isAfter(today)) {
            membership.setStatus(MembershipStatus.PENDING);
        } else if (today.isAfter(end)) {
            membership.setStatus(MembershipStatus.EXPIRED);
        } else {
            membership.setStatus(MembershipStatus.ACTIVE);
        }
        Membership saved = membershipRepository.save(membership);
        auditService.record(AuditActions.MEMBERSHIP_DATES_UPDATED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(),
                Map.of("startDate", start.toString(), "endDate", end.toString()));
        publish(saved, ChangeType.DATES_UPDATED);
        return saved;
    }

    @Transactional
    public void delete(String membershipPublicId, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);

        if (membership.getStatus() != MembershipStatus.PENDING && membership.getStatus() != MembershipStatus.ACTIVE) {
            throw CommonExceptions.badRequest(
                    "Only pending/active memberships can be deleted");
        }

        if (membership.getPaymentStatus() != MembershipPaymentStatus.UNPAID) {
            throw CommonExceptions.badRequest(
                    "A membership with payment history cannot be deleted");
        }

        if (membership.getDeviceSyncState() != DeviceSyncState.NOT_SYNCED) {
            throw CommonExceptions.badRequest(
                    "A members");
        }

        membership.setDeleted(true);
        membershipRepository.save(membership);

        auditService.record(
                AuditActions.MEMBERSHIP_DELETED,
                AuditActions.RESULT_SUCCESS,
                "Membership",
                membership.getPublicId(),
                null
        );
    }

    private void publish(Membership membership, ChangeType type) {
        eventPublisher.publishEvent(new MembershipChangedEvent(
                membership.getTenantId(), membership.getMemberId(), membership.getId(), type));
    }

    private Membership build(Long tenantId, Long memberId, MembershipPlan plan, LocalDate start, LocalDate end) {
        requireEndOnOrAfterStart(start, end);
        LocalDate today = LocalDate.now();
        MembershipStatus status = start.isAfter(today) ? MembershipStatus.PENDING : MembershipStatus.ACTIVE;
        return new Membership(tenantId, memberId, plan.getId(), plan.getName(), plan.getPrice(),
                plan.getCurrency(), start, end, status);
    }

    private static LocalDate resolveEnd(LocalDate start, LocalDate requestedEnd, MembershipPlan plan) {
        if (requestedEnd != null) {
            return requestedEnd;
        }
        return start.plusDays(Math.max(plan.getDurationDays() - 1, 0));
    }

    private static void requireEndOnOrAfterStart(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw CommonExceptions.badRequest("End date must be on or after start date");
        }
    }
}
