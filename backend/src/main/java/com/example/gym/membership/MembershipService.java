package com.example.gym.membership;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.membership.dto.MembershipRequests.CreateMembership;
import com.example.gym.membership.dto.MembershipRequests.RenewMembership;
import com.example.gym.plan.MembershipPlan;
import com.example.gym.plan.PlanService;
import com.example.gym.plan.PlanStatus;
import com.example.gym.plan.MembershipPlanRepository;
import com.example.gym.tenant.TenantGuard;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Membership lifecycle: create, renew (preserving history), freeze/unfreeze (pausing validity),
 * and cancel. Device authorization is NOT performed here — memberships stay {@code NOT_SYNCED}
 * until the Phase 3 sync engine runs; we never fabricate a device result.
 */
@Service
public class MembershipService {

    private final MembershipRepository membershipRepository;
    private final MembershipPlanRepository planRepository;
    private final MemberService memberService;
    private final PlanService planService;
    private final AuditService auditService;

    public MembershipService(MembershipRepository membershipRepository,
                             MembershipPlanRepository planRepository,
                             MemberService memberService,
                             PlanService planService,
                             AuditService auditService) {
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
        this.memberService = memberService;
        this.planService = planService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Membership getByPublicId(String publicId, Long tenantId) {
        Membership membership = membershipRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Membership"));
        TenantGuard.check(membership.getTenantId(), tenantId, "Membership");
        return membership;
    }

    @Transactional(readOnly = true)
    public List<Membership> listForMember(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        return membershipRepository.findByMemberIdOrderByStartDateDesc(member.getId());
    }

    @Transactional
    public Membership create(CreateMembership request, Long tenantId) {
        Member member = memberService.getByPublicId(request.memberId(), tenantId);
        MembershipPlan plan = planService.requireActive(request.planId(), tenantId);
        LocalDate start = request.startDate() != null ? request.startDate() : LocalDate.now();
        Membership membership = build(tenantId, member.getId(), plan, start);
        Membership saved = membershipRepository.save(membership);

        auditService.record(AuditActions.MEMBERSHIP_CREATED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(),
                Map.of("memberId", member.getPublicId(), "plan", plan.getName()));
        return saved;
    }

    @Transactional
    public Membership renew(String membershipPublicId, RenewMembership request, Long tenantId) {
        Membership current = getByPublicId(membershipPublicId, tenantId);
        MembershipPlan plan = resolveRenewalPlan(current, request.planId(), tenantId);

        LocalDate today = LocalDate.now();
        LocalDate start;
        if (request.startDate() != null) {
            start = request.startDate();
        } else {
            start = current.getEndDate().isBefore(today) ? today : current.getEndDate().plusDays(1);
        }

        // History is preserved: the old membership row is left untouched; a new one is created.
        Membership renewal = build(tenantId, current.getMemberId(), plan, start);
        Membership saved = membershipRepository.save(renewal);

        auditService.record(AuditActions.MEMBERSHIP_RENEWED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(),
                Map.of("renewedFrom", current.getPublicId(), "plan", plan.getName()));
        return saved;
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
        return saved;
    }

    private Membership build(Long tenantId, Long memberId, MembershipPlan plan, LocalDate start) {
        // End date is inclusive: a 30-day plan starting on the 1st is valid through the 30th.
        LocalDate end = start.plusDays(Math.max(plan.getDurationDays() - 1, 0));
        LocalDate today = LocalDate.now();
        MembershipStatus status = start.isAfter(today) ? MembershipStatus.PENDING : MembershipStatus.ACTIVE;
        return new Membership(tenantId, memberId, plan.getId(), plan.getName(), plan.getPrice(),
                plan.getCurrency(), start, end, status);
    }

    private MembershipPlan resolveRenewalPlan(Membership current, String requestedPlanPublicId,
                                              Long tenantId) {
        if (StringUtils.hasText(requestedPlanPublicId)) {
            return planService.requireActive(requestedPlanPublicId, tenantId);
        }
        if (current.getPlanId() == null) {
            throw CommonExceptions.badRequest("Original plan is unavailable; specify a plan to renew with");
        }
        MembershipPlan plan = planRepository.findById(current.getPlanId())
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> CommonExceptions.badRequest(
                        "Original plan is unavailable; specify a plan to renew with"));
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw CommonExceptions.badRequest("Original plan is archived; specify a plan to renew with");
        }
        return plan;
    }
}
