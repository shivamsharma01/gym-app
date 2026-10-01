package com.example.gym.membership;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.member.MemberRepository;
import com.example.gym.common.web.PageResponse;
import com.example.gym.membership.dto.MembershipRequests.CreateMembership;
import com.example.gym.membership.dto.MembershipRequests.RenewMembership;
import com.example.gym.membership.dto.MembershipRequests.UpdateMembership;
import com.example.gym.membership.dto.MembershipRequests.UpdateMembershipDates;
import com.example.gym.membership.dto.PlanActiveMemberResponse;
import com.example.gym.membership.dto.PlanActivityCount;
import com.example.gym.membership.dto.PlanRosterResponse;
import com.example.gym.plan.MembershipPlan;
import com.example.gym.plan.PlanService;
import com.example.gym.plan.PlanStatus;
import com.example.gym.plan.MembershipPlanRepository;
import com.example.gym.tenant.TenantGuard;
import com.example.gym.membership.MembershipChangedEvent.ChangeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.example.gym.security.SecurityUtils;

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
    private final MemberRepository memberRepository;
    private final PlanService planService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public MembershipService(MembershipRepository membershipRepository,
                             MembershipPlanRepository planRepository,
                             MemberService memberService,
                             MemberRepository memberRepository,
                             PlanService planService,
                             AuditService auditService,
                             ApplicationEventPublisher eventPublisher) {
        this.membershipRepository = membershipRepository;
        this.planRepository = planRepository;
        this.memberService = memberService;
        this.memberRepository = memberRepository;
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

    private static final List<MembershipStatus> OPEN_FOR_ROSTER =
            List.of(MembershipStatus.ACTIVE, MembershipStatus.PENDING);

    @Transactional(readOnly = true)
    public List<PlanRosterResponse> planRoster(Long tenantId) {
        LocalDate today = LocalDate.now();
        Map<Long, PlanActivityCount> counts = membershipRepository
                .countActiveByPlan(tenantId, OPEN_FOR_ROSTER, today, today.plusDays(7)).stream()
                .collect(Collectors.toMap(PlanActivityCount::planId, Function.identity()));
        return planRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(plan -> {
                    PlanActivityCount count = counts.get(plan.getId());
                    long active = count == null || count.activeMembers() == null ? 0 : count.activeMembers();
                    long expiring = count == null || count.expiringWithin7Days() == null
                            ? 0 : count.expiringWithin7Days();
                    return PlanRosterResponse.from(plan, active, expiring);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanActiveMemberResponse> activeMembers(String planPublicId, Long tenantId,
                                                                int page, int size) {
        MembershipPlan plan = planService.getByPublicId(planPublicId, tenantId);
        LocalDate today = LocalDate.now();
        LocalDate expiringThrough = today.plusDays(7);
        var memberships = membershipRepository.findCoveringPlan(
                tenantId, plan.getId(), OPEN_FOR_ROSTER, today,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "endDate").and(Sort.by("id"))));
        Map<Long, com.example.gym.member.Member> members = memberRepository
                .findAllById(memberships.map(Membership::getMemberId).toList()).stream()
                .collect(Collectors.toMap(com.example.gym.member.Member::getId, Function.identity()));
        return PageResponse.from(memberships, membership -> {
            var member = members.get(membership.getMemberId());
            return new PlanActiveMemberResponse(
                    member == null ? null : member.getPublicId(),
                    member == null ? null : member.getMemberCode(),
                    member == null ? "Unknown member" : member.getFullName(),
                    member == null ? null : member.getPhone(),
                    membership.getStartDate(),
                    membership.getEndDate(),
                    !membership.getEndDate().isAfter(expiringThrough));
        });
    }

    @Transactional(readOnly = true)
    public List<Membership> listForMember(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        return membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId());
    }

    private Member requireActiveMemberForMembershipMutation(Long memberId, Long tenantId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> CommonExceptions.notFound("Member"));
        TenantGuard.check(member.getTenantId(), tenantId, "Member");
        if (member.getStatus() != com.example.gym.member.MemberStatus.ACTIVE) {
            throw CommonExceptions.conflict(
                    "Member is inactive. Reactivate the member before changing memberships.");
        }
        return member;
    }

    @Transactional
    public Membership create(CreateMembership request, Long tenantId) {
        Member member = memberService.getByPublicId(request.memberId(), tenantId);
        if (member.getStatus() != com.example.gym.member.MemberStatus.ACTIVE) {
            throw CommonExceptions.conflict(
                    "Member is inactive. Reactivate the member before creating a membership.");
        }

        MembershipPlan plan = planService.requireActive(request.planId(), tenantId);

        if (plan.getDurationDays() >= 3650 && member.getDeviceAuthority() != com.example.gym.member.DeviceAuthority.ADMIN) {
            throw CommonExceptions.conflict("Lifetime pass (10 years) can only be assigned to members with Admin authority.");
        }

        LocalDate start =
                request.startDate() != null
                        ? request.startDate()
                        : LocalDate.now();

        LocalDate end =
                resolveEnd(start, request.endDate(), plan);

        requireNoOverlappingMembership(
                member.getId(),
                start,
                end,
                null
        );

        Membership membership =
                build(tenantId, member.getId(), plan, start, end);

        BigDecimal discount =
                validateAndAuthorizeDiscount(
                        request.discountAmount(),
                        plan.getPrice()
                );

        membership.setDiscountAmount(discount);
        recordDiscountApproval(membership, discount);

        Membership saved = membershipRepository.save(membership);

        FlowLog.info("membership", "created id={} member={} plan={} discount={}",
                saved.getPublicId(), member.getPublicId(), plan.getName(), discount);
        auditService.record(
                AuditActions.MEMBERSHIP_CREATED,
                AuditActions.RESULT_SUCCESS,
                "Membership",
                saved.getPublicId(),
                Map.of(
                        "memberId", member.getPublicId(),
                        "plan", plan.getName(),
                        "discountAmount", discount.toPlainString()
                )
        );
        auditDiscount(saved);

        publish(saved, ChangeType.CREATED);
        return saved;
    }

    private void requireNoOverlappingMembership(
            Long memberId,
            LocalDate start,
            LocalDate end,
            Long excludeMembershipId
    ) {
        boolean overlaps;

        if (excludeMembershipId == null) {
            overlaps =
                    membershipRepository
                            .existsByMemberIdAndDeletedFalseAndStatusNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                                    memberId,
                                    MembershipStatus.CANCELLED,
                                    end,
                                    start
                            );
        } else {
            overlaps =
                    membershipRepository
                            .findByMemberIdAndDeletedFalseOrderByStartDateDesc(memberId)
                            .stream()
                            .anyMatch(existing ->
                                    !existing.getId().equals(excludeMembershipId)
                                            && existing.getStatus() != MembershipStatus.CANCELLED
                                            && !existing.getStartDate().isAfter(end)
                                            && !existing.getEndDate().isBefore(start)
                            );
        }

        if (overlaps) {
            throw CommonExceptions.badRequest(
                    "Membership dates overlap with an existing membership. "
                            + "Cancel the existing membership before creating another one."
            );
        }
    }

    @Transactional
    public Membership renew(
            String membershipPublicId,
            RenewMembership request,
            Long tenantId
    ) {
        Membership current =
                getByPublicId(membershipPublicId, tenantId);
        Member member = requireActiveMemberForMembershipMutation(current.getMemberId(), tenantId);

        MembershipPlan plan =
                resolveRenewalPlan(
                        current,
                        request.planId(),
                        tenantId
                );

        if (plan.getDurationDays() >= 3650 && member.getDeviceAuthority() != com.example.gym.member.DeviceAuthority.ADMIN) {
            throw CommonExceptions.conflict("Lifetime pass (10 years) can only be assigned to members with Admin authority.");
        }


        LocalDate start = request.startDate();

        if (start == null) {
            start = current.getEndDate().plusDays(1);
        }

        if (!start.isAfter(current.getEndDate())) {
            throw CommonExceptions.badRequest(
                    "Renewal can only start after the current membership end date"
            );
        }

        LocalDate end = request.endDate();

        if (end.isBefore(start)) {
            throw CommonExceptions.badRequest(
                    "End date cannot be before start date"
            );
        }

        requireNoOverlappingMembership(
                current.getMemberId(),
                start,
                end,
                current.getId()
        );

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

        BigDecimal discount =
                validateAndAuthorizeDiscount(
                        request.discountAmount(),
                        plan.getPrice()
                );

        BigDecimal amountToCollect;

        if (qualifiesForCredit) {
            amountToCollect = plan.getPrice()
                    .subtract(current.getPrice())
                    .max(BigDecimal.ZERO);
        } else {
            amountToCollect = plan.getPrice();
        }

        amountToCollect = amountToCollect
                .subtract(discount)
                .max(BigDecimal.ZERO);

        Membership renewal = build(
                tenantId,
                current.getMemberId(),
                plan,
                start,
                end
        );


        renewal.setDiscountAmount(discount);
        recordDiscountApproval(renewal, discount);

        renewal.setAmountPaid(BigDecimal.ZERO);

        renewal.setPaymentStatus(
                renewal.getNetAmount().compareTo(BigDecimal.ZERO) == 0
                        ? MembershipPaymentStatus.PAID
                        : MembershipPaymentStatus.UNPAID
        );

        Membership saved =
                membershipRepository.save(renewal);

        FlowLog.info("membership", "renewed id={} from={} plan={} discount={}",
                saved.getPublicId(), current.getPublicId(), plan.getName(), discount);
        auditService.record(
                AuditActions.MEMBERSHIP_RENEWED,
                AuditActions.RESULT_SUCCESS,
                "Membership",
                saved.getPublicId(),
                Map.of(
                        "renewedFrom", current.getPublicId(),
                        "plan", plan.getName(),
                        "discountAmount", discount.toPlainString()
                )
        );
        auditDiscount(saved);

        publish(saved, ChangeType.RENEWED);

        return saved;
    }

    private MembershipPlan resolveRenewalPlan(
            Membership current,
            String requestedPlanId,
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

        return planRepository.findByPublicId(requestedPlanId)
                .filter(plan -> plan.getTenantId().equals(tenantId))
                .orElseThrow(() ->
                        CommonExceptions.notFound(
                                "Membership plan not found"
                        )
                );
    }

    private void auditDiscount(Membership saved) {
        if (saved.getDiscountAmount() == null || saved.getDiscountAmount().signum() <= 0) {
            return;
        }
        String approver = saved.getDiscountApprovedByUsername() == null
                ? ""
                : saved.getDiscountApprovedByUsername();
        FlowLog.info("membership", "discount approved membership={} amount={} by={}",
                saved.getPublicId(), saved.getDiscountAmount(), approver);
        auditService.record(AuditActions.MEMBERSHIP_DISCOUNT, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(),
                Map.of("discountAmount", saved.getDiscountAmount().toPlainString(), "approvedBy", approver));
    }

    private void recordDiscountApproval(Membership membership, BigDecimal discount) {
        if (discount == null || discount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        var principal = SecurityUtils.currentPrincipal();

        membership.setDiscountApprovedByUserId(principal.getUserId());
        membership.setDiscountApprovedByUsername(principal.getUsername());
        membership.setDiscountApprovedAt(Instant.now());
    }

    @Transactional
    public Membership freeze(String membershipPublicId, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        requireActiveMemberForMembershipMutation(membership.getMemberId(), tenantId);
        LocalDate today = LocalDate.now();
        if (membership.effectiveStatus(today) != MembershipStatus.ACTIVE) {
            throw CommonExceptions.badRequest("Only an active membership can be frozen");
        }
        membership.setStatus(MembershipStatus.FROZEN);
        membership.setFrozenOn(today);
        Membership saved = membershipRepository.save(membership);
        FlowLog.info("membership", "frozen id={}", saved.getPublicId());
        auditService.record(AuditActions.MEMBERSHIP_FROZEN, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), null);
        publish(saved, ChangeType.FROZEN);
        return saved;
    }

    @Transactional
    public Membership unfreeze(String membershipPublicId, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        requireActiveMemberForMembershipMutation(membership.getMemberId(), tenantId);
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
        FlowLog.info("membership", "unfrozen id={} extendedDays={}", saved.getPublicId(), frozenDays);
        auditService.record(AuditActions.MEMBERSHIP_UNFROZEN, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), Map.of("extendedDays", frozenDays));
        publish(saved, ChangeType.UNFROZEN);
        return saved;
    }

    @Transactional
    public Membership cancel(String membershipPublicId, String reason, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        requireActiveMemberForMembershipMutation(membership.getMemberId(), tenantId);
        if (membership.getStatus() == MembershipStatus.CANCELLED) {
            throw CommonExceptions.badRequest("Membership is already cancelled");
        }
        membership.setStatus(MembershipStatus.CANCELLED);
        membership.setCancelledOn(LocalDate.now());
        membership.setCancelReason(StringUtils.hasText(reason) ? reason : null);
        Membership saved = membershipRepository.save(membership);
        FlowLog.info("membership", "cancelled id={}", saved.getPublicId());
        auditService.record(AuditActions.MEMBERSHIP_CANCELLED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), reason == null ? null : Map.of("reason", reason));
        publish(saved, ChangeType.CANCELLED);
        return saved;
    }

    @Transactional
    public Membership updateMembership(String membershipPublicId, UpdateMembership request, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        requireActiveMemberForMembershipMutation(membership.getMemberId(), tenantId);

        if (membership.getStatus() == MembershipStatus.CANCELLED
                || membership.getStatus() == MembershipStatus.FROZEN) {
            throw CommonExceptions.badRequest("Cannot edit a frozen or cancelled membership");
        }

        MembershipPlan plan = planService.requireActive(request.planId(), tenantId);
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        requireEndOnOrAfterStart(start, end);

        if (membershipRepository.existsOverlappingMembership(
                membership.getMemberId(),
                membership.getPublicId(),
                start,
                end)) {
            throw CommonExceptions.badRequest(
                    "Membership dates overlap with an existing membership. " +
                            "Cancel the existing membership before changing these dates."
            );
        }

        if (membership.getAmountPaid().signum() > 0
                && !membership.getCurrency().equals(plan.getCurrency())) {
            throw CommonExceptions.badRequest(
                    "Cannot change the plan currency after payments have been recorded"
            );
        }

        BigDecimal discount = validateAndAuthorizeDiscount(
                request.discountAmount(),
                plan.getPrice()
        );

        membership.setPlanId(plan.getId());
        membership.setPlanName(plan.getName());
        membership.setPrice(plan.getPrice());
        membership.setCurrency(plan.getCurrency());
        membership.setStartDate(start);
        membership.setEndDate(end);
        membership.setDiscountAmount(discount);
        recordDiscountApproval(membership, discount);

        LocalDate today = LocalDate.now();
        if (start.isAfter(today)) {
            membership.setStatus(MembershipStatus.PENDING);
        } else if (today.isAfter(end)) {
            membership.setStatus(MembershipStatus.EXPIRED);
        } else {
            membership.setStatus(MembershipStatus.ACTIVE);
        }

        BigDecimal netAmount = membership.getNetAmount();
        BigDecimal amountPaid = membership.getAmountPaid();
        if (amountPaid.signum() <= 0) {
            membership.setPaymentStatus(
                    netAmount.signum() <= 0
                            ? MembershipPaymentStatus.PAID
                            : MembershipPaymentStatus.UNPAID
            );
        } else if (amountPaid.compareTo(netAmount) >= 0) {
            membership.setPaymentStatus(MembershipPaymentStatus.PAID);
        } else {
            membership.setPaymentStatus(MembershipPaymentStatus.PARTIAL);
        }

        Membership saved = membershipRepository.save(membership);

        FlowLog.info("membership", "updated id={} plan={} discount={}",
                saved.getPublicId(), plan.getName(), discount);
        auditService.record(
                AuditActions.MEMBERSHIP_DATES_UPDATED,
                AuditActions.RESULT_SUCCESS,
                "Membership",
                saved.getPublicId(),
                Map.of(
                        "plan", plan.getName(),
                        "startDate", start.toString(),
                        "endDate", end.toString(),
                        "discountAmount", discount.toPlainString(),
                        "amountPaid", amountPaid.toPlainString(),
                        "paymentStatus", saved.getPaymentStatus().name()
                )
        );
        auditDiscount(saved);

        publish(saved, ChangeType.DATES_UPDATED);
        return saved;
    }

    @Transactional
    public Membership updateDates(String membershipPublicId, UpdateMembershipDates request, Long tenantId) {
        Membership membership = getByPublicId(membershipPublicId, tenantId);
        requireActiveMemberForMembershipMutation(membership.getMemberId(), tenantId);
        if (membership.getStatus() == MembershipStatus.CANCELLED
                || membership.getStatus() == MembershipStatus.FROZEN) {
            throw CommonExceptions.badRequest("Cannot change dates on a frozen or cancelled membership");
        }
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();

        requireEndOnOrAfterStart(start, end);

        if (membershipRepository.existsOverlappingMembership(
                membership.getMemberId(),
                membership.getPublicId(),
                start,
                end
        )) {
            throw CommonExceptions.badRequest(
                    "Membership dates overlap with an existing membership. " +
                            "Cancel the existing membership before changing these dates."
            );
        }

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

    /**
     * Dates edited on a device. Applied as the device holds them (also on a frozen membership, and
     * even if they now overlap another membership). Returns true when they overlap, so the caller
     * can flag it for staff.
     */
    @Transactional
    public boolean applyDatesFromDevice(Membership membership, LocalDate start, LocalDate end) {
        requireEndOnOrAfterStart(start, end);
        boolean overlaps = membershipRepository.existsOverlappingMembership(
                membership.getMemberId(), membership.getPublicId(), start, end);
        membership.setStartDate(start);
        membership.setEndDate(end);
        if (membership.getStatus() != MembershipStatus.FROZEN) {
            LocalDate today = LocalDate.now();
            membership.setStatus(start.isAfter(today) ? MembershipStatus.PENDING
                    : today.isAfter(end) ? MembershipStatus.EXPIRED : MembershipStatus.ACTIVE);
        }
        Membership saved = membershipRepository.save(membership);
        auditService.recordSystem(AuditActions.MEMBERSHIP_DATES_UPDATED, AuditActions.RESULT_SUCCESS,
                "Membership", saved.getPublicId(), saved.getTenantId(), "device",
                Map.of("startDate", start.toString(), "endDate", end.toString(), "source", "device",
                        "overlapsAnotherMembership", overlaps));
        publish(saved, ChangeType.DATES_UPDATED);
        return overlaps;
    }

    private BigDecimal validateDiscount(BigDecimal discountAmount, BigDecimal planPrice) {
        BigDecimal discount = discountAmount == null
                ? BigDecimal.ZERO
                : discountAmount;

        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            throw CommonExceptions.badRequest(
                    "Discount cannot be negative"
            );
        }

        if (discount.compareTo(planPrice) > 0) {
            throw CommonExceptions.badRequest(
                    "Discount cannot be greater than the plan amount"
            );
        }

        return discount;
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

    private BigDecimal validateAndAuthorizeDiscount(
            BigDecimal discountAmount,
            BigDecimal planPrice
    ) {
        BigDecimal discount = discountAmount == null
                ? BigDecimal.ZERO
                : discountAmount;

        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            throw CommonExceptions.badRequest(
                    "Discount cannot be negative"
            );
        }

        if (discount.compareTo(planPrice) > 0) {
            throw CommonExceptions.badRequest(
                    "Discount cannot be greater than the plan amount"
            );
        }

        if (discount.compareTo(BigDecimal.ZERO) > 0) {
            boolean authorized = SecurityUtils.currentPrincipal()
                    .getAuthorities()
                    .stream()
                    .anyMatch(a ->
                            "MEMBERSHIP_DISCOUNT_APPROVE"
                                    .equals(a.getAuthority())
                    );

            if (!authorized) {
                throw CommonExceptions.forbidden(
                        "Only an authorized administrator can approve membership discounts"
                );
            }
        }

        return discount;
    }

    private static void requireEndOnOrAfterStart(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw CommonExceptions.badRequest("End date must be on or after start date");
        }
    }
}
