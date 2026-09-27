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

import lombok.extern.slf4j.Slf4j;

import com.example.gym.membership.MembershipChangedEvent.ChangeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
@Slf4j
@Service
public class MembershipService {

	private final MembershipRepository membershipRepository;
	private final MembershipPlanRepository planRepository;
	private final MemberService memberService;
	private final PlanService planService;
	private final AuditService auditService;
	private final ApplicationEventPublisher eventPublisher;

	public MembershipService(MembershipRepository membershipRepository, MembershipPlanRepository planRepository,
			MemberService memberService, PlanService planService, AuditService auditService,
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

		log.debug("Fetching membership: publicId={}, tenantId={}", publicId, tenantId);

		Membership membership = membershipRepository.findByPublicIdAndDeletedFalse(publicId)
				.orElseThrow(() -> CommonExceptions.notFound("Membership"));
		TenantGuard.check(membership.getTenantId(), tenantId, "Membership");

		log.debug("Membership fetched successfully: publicId={}, tenantId={}", publicId, tenantId);

		return membership;
	}

	@Transactional(readOnly = true)
	public List<Membership> listForMember(String memberPublicId, Long tenantId) {

		log.debug("Listing memberships for member: memberPublicId={}, tenantId={}", memberPublicId, tenantId);

		Member member = memberService.getByPublicId(memberPublicId, tenantId);
		List<Membership> memberships = membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(member.getId());

		log.debug("Memberships fetched: memberPublicId={}, tenantId={}, count={}", memberPublicId, tenantId,
				memberships.size());

		return memberships;
	}

	@Transactional
	public Membership create(CreateMembership request, Long tenantId) {

		log.info("Creating membership: memberPublicId={}, planPublicId={}, tenantId={}", request.memberId(),
				request.planId(), tenantId);
		Member member = memberService.getByPublicId(request.memberId(), tenantId);
		MembershipPlan plan = planService.requireActive(request.planId(), tenantId);
		LocalDate start = request.startDate() != null ? request.startDate() : LocalDate.now();
		LocalDate end = resolveEnd(start, request.endDate(), plan);
        
		requireNoOverlappingMembership(
                member.getId(),
                start,
                end,
                null
        );
		Membership membership = build(tenantId, member.getId(), plan, start, end);
        BigDecimal discount =
                validateAndAuthorizeDiscount(
                        request.discountAmount(),
                        plan.getPrice()
                );

        membership.setDiscountAmount(discount);
        recordDiscountApproval(membership, discount);
		Membership saved = membershipRepository.save(membership);

		log.info("Membership created successfully: publicId={}, memberId={}, tenantId={}", saved.getPublicId(),
				member.getId(), tenantId);

        auditService.record(
                AuditActions.MEMBERSHIP_CREATED,
                AuditActions.RESULT_SUCCESS,
                "Membership",
                saved.getPublicId(),
                Map.of(
                        "memberId", member.getPublicId(),
                        "plan", plan.getName()
                )
        );
        publish(saved, ChangeType.CREATED);
		return saved;
	}

	@Transactional
	public Membership renew(String membershipPublicId, RenewMembership request, Long tenantId) {

		log.info("Renewing membership: publicId={}, requestedPlan={}, tenantId={}", membershipPublicId,
				request.planId(), tenantId);

		
        Membership current =
                getByPublicId(membershipPublicId, tenantId);

        MembershipPlan plan =
                resolveRenewalPlan(
                        current,
                        request.planId(),
                        tenantId
                );


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
		log.info("Membership renewed successfully: oldPublicId={}, newPublicId={}, tenantId={}", membershipPublicId,
				saved.getPublicId(), tenantId);
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

	@Transactional
	public Membership freeze(String membershipPublicId, Long tenantId) {

		log.info("Freezing membership: publicId={}, tenantId={}", membershipPublicId, tenantId);

		Membership membership = getByPublicId(membershipPublicId, tenantId);
		LocalDate today = LocalDate.now();

		MembershipStatus currentStatus = membership.effectiveStatus(today);
		log.debug("Membership status before freeze: publicId={}, status={}", membershipPublicId, currentStatus);

		if (membership.effectiveStatus(today) != MembershipStatus.ACTIVE) {
			log.warn("Cannot freeze membership because it is not active: publicId={}, status={}", membershipPublicId,
					currentStatus);
			throw CommonExceptions.badRequest("Only an active membership can be frozen");
		}
		membership.setStatus(MembershipStatus.FROZEN);
		membership.setFrozenOn(today);
		Membership saved = membershipRepository.save(membership);

		log.info("Membership frozen successfully: publicId={}, frozenOn={}, tenantId={}", saved.getPublicId(), today,
				tenantId);
		auditService.record(AuditActions.MEMBERSHIP_FROZEN, AuditActions.RESULT_SUCCESS, "Membership",
				saved.getPublicId(), null);
		publish(saved, ChangeType.FROZEN);
		return saved;
	}

	@Transactional
	public Membership unfreeze(String membershipPublicId, Long tenantId) {

		log.info("Unfreezing membership: publicId={}, tenantId={}", membershipPublicId, tenantId);

		Membership membership = getByPublicId(membershipPublicId, tenantId);
		if (membership.getStatus() != MembershipStatus.FROZEN || membership.getFrozenOn() == null) {
			log.warn("Cannot unfreeze membership because it is not frozen: publicId={}, status={}", membershipPublicId,
					membership.getStatus());
			throw CommonExceptions.badRequest("Membership is not frozen");
		}
		LocalDate today = LocalDate.now();
		int frozenDays = (int) ChronoUnit.DAYS.between(membership.getFrozenOn(), today);

		log.debug("Freeze duration calculated: publicId={}, frozenOn={}, today={}, frozenDays={}", membershipPublicId,
				membership.getFrozenOn(), today, frozenDays);

		if (frozenDays > 0) {
			// Extend validity by the frozen duration so members don't lose paid time.
			membership.setEndDate(membership.getEndDate().plusDays(frozenDays));
			membership.setFreezeDaysAccumulated(membership.getFreezeDaysAccumulated() + frozenDays);
		}
		membership.setFrozenOn(null);
		membership
				.setStatus(today.isAfter(membership.getEndDate()) ? MembershipStatus.EXPIRED : MembershipStatus.ACTIVE);
		Membership saved = membershipRepository.save(membership);

		log.info("Membership unfrozen successfully: publicId={}, extendedDays={}, newEndDate={}, status={}",
				saved.getPublicId(), frozenDays, saved.getEndDate(), saved.getStatus());

		auditService.record(AuditActions.MEMBERSHIP_UNFROZEN, AuditActions.RESULT_SUCCESS, "Membership",
				saved.getPublicId(), Map.of("extendedDays", frozenDays));
		publish(saved, ChangeType.UNFROZEN);
		return saved;
	}

	@Transactional
	public Membership cancel(String membershipPublicId, String reason, Long tenantId) {

		log.info("Cancelling membership: publicId={}, tenantId={}", membershipPublicId, tenantId);

		Membership membership = getByPublicId(membershipPublicId, tenantId);
		if (membership.getStatus() == MembershipStatus.CANCELLED) {
			log.warn("Membership is already cancelled: publicId={}", membershipPublicId);
			throw CommonExceptions.badRequest("Membership is already cancelled");
		}
		membership.setStatus(MembershipStatus.CANCELLED);
		membership.setCancelledOn(LocalDate.now());
		membership.setCancelReason(StringUtils.hasText(reason) ? reason : null);
		Membership saved = membershipRepository.save(membership);

		log.info("Membership cancelled successfully: publicId={}, cancelledOn={}, tenantId={}", saved.getPublicId(),
				saved.getCancelledOn(), tenantId);
		auditService.record(AuditActions.MEMBERSHIP_CANCELLED, AuditActions.RESULT_SUCCESS, "Membership",
				saved.getPublicId(), reason == null ? null : Map.of("reason", reason));
		publish(saved, ChangeType.CANCELLED);
		return saved;
	}

	@Transactional
	public Membership updateDates(String membershipPublicId, UpdateMembershipDates request, Long tenantId) {

		log.info("Updating membership dates: publicId={}, tenantId={}", membershipPublicId, tenantId);

		Membership membership = getByPublicId(membershipPublicId, tenantId);
		if (membership.getStatus() == MembershipStatus.CANCELLED || membership.getStatus() == MembershipStatus.FROZEN) {

			log.warn("Cannot update membership dates: publicId={}, status={}", membershipPublicId,
					membership.getStatus());
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

		log.info("Membership dates updated successfully: publicId={}, start={}, end={}, status={}", saved.getPublicId(),
				saved.getStartDate(), saved.getEndDate(), saved.getStatus());

		auditService.record(AuditActions.MEMBERSHIP_DATES_UPDATED, AuditActions.RESULT_SUCCESS, "Membership",
				saved.getPublicId(), Map.of("startDate", start.toString(), "endDate", end.toString()));
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

		log.debug("Publishing membership change event: publicId={}, memberId={}, tenantId={}, changeType={}",
				membership.getPublicId(), membership.getMemberId(), membership.getTenantId(), type);

		eventPublisher.publishEvent(new MembershipChangedEvent(membership.getTenantId(), membership.getMemberId(),
				membership.getId(), type));
	}

	private Membership build(Long tenantId, Long memberId, MembershipPlan plan, LocalDate start, LocalDate end) {

		log.debug("Building membership: tenantId={}, memberId={}, planId={}, start={}, end={}", tenantId, memberId,
				plan.getId(), start, end);

		requireEndOnOrAfterStart(start, end);
		LocalDate today = LocalDate.now();
		MembershipStatus status = start.isAfter(today) ? MembershipStatus.PENDING : MembershipStatus.ACTIVE;

		log.debug("Membership status determined: memberId={}, status={}", memberId, status);

		return new Membership(tenantId, memberId, plan.getId(), plan.getName(), plan.getPrice(), plan.getCurrency(),
				start, end, status);
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
    
    private void recordDiscountApproval(Membership membership, BigDecimal discount) {
        if (discount == null || discount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        var principal = SecurityUtils.currentPrincipal();

        membership.setDiscountApprovedByUserId(principal.getUserId());
        membership.setDiscountApprovedByUsername(principal.getUsername());
        membership.setDiscountApprovedAt(Instant.now());
    }
    
	private static LocalDate resolveEnd(LocalDate start, LocalDate requestedEnd, MembershipPlan plan) {
		if (requestedEnd != null) {
			log.debug("Using requested membership end date: start={}, end={}", start, requestedEnd);
			return requestedEnd;
		}
		LocalDate calculatedEnd = start.plusDays(Math.max(plan.getDurationDays() - 1, 0));

		log.debug("Calculated membership end date from plan duration: start={}, durationDays={}, end={}", start,
				plan.getDurationDays(), calculatedEnd);

		return calculatedEnd;
	}

	private static void requireEndOnOrAfterStart(LocalDate start, LocalDate end) {
		if (end.isBefore(start)) {
			log.warn("Invalid membership dates: start={}, end={}", start, end);
			throw CommonExceptions.badRequest("End date must be on or after start date");
		}
	}

	private MembershipPlan resolveRenewalPlan(Membership current, String requestedPlanPublicId, Long tenantId) {

		log.debug("Resolving renewal plan: membershipPublicId={}, requestedPlan={}, tenantId={}", current.getPublicId(),
				requestedPlanPublicId, tenantId);

		if (StringUtils.hasText(requestedPlanPublicId)) {

			log.debug("Using explicitly requested renewal plan: membershipPublicId={}, planPublicId={}",
					current.getPublicId(), requestedPlanPublicId);

			return planService.requireActive(requestedPlanPublicId, tenantId);
		}
		if (current.getPlanId() == null) {

			log.warn("Original plan unavailable for renewal: membershipPublicId={}", current.getPublicId());

			throw CommonExceptions.badRequest("Original plan is unavailable; specify a plan to renew with");
		}
		MembershipPlan plan = planRepository.findById(current.getPlanId()).filter(p -> p.getTenantId().equals(tenantId))
				.orElseThrow(() -> {
					log.warn("Original plan not found for renewal: membershipPublicId={}, planId={}, tenantId={}",
							current.getPublicId(), current.getPlanId(), tenantId);
					return CommonExceptions.badRequest("Original plan is unavailable; specify a plan to renew with");
				});
		if (plan.getStatus() != PlanStatus.ACTIVE) {

			log.warn("Original plan is not active for renewal: membershipPublicId={}, planId={}, status={}",
					current.getPublicId(), plan.getId(), plan.getStatus());

			throw CommonExceptions.badRequest("Original plan is archived; specify a plan to renew with");
		}
		return plan;
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

}
