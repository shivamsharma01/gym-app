package com.example.gym.plan;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.plan.dto.PlanRequests.CreatePlan;
import com.example.gym.plan.dto.PlanRequests.UpdatePlan;
import com.example.gym.tenant.TenantGuard;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private final MembershipPlanRepository planRepository;
    private final AuditService auditService;

    public PlanService(MembershipPlanRepository planRepository, AuditService auditService) {
        this.planRepository = planRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<MembershipPlan> list(Long tenantId, PlanStatus status, Pageable pageable) {
        return status == null
                ? planRepository.findByTenantId(tenantId, pageable)
                : planRepository.findByTenantIdAndStatus(tenantId, status, pageable);
    }

    @Transactional(readOnly = true)
    public MembershipPlan getByPublicId(String publicId, Long tenantId) {
        MembershipPlan plan = planRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Plan"));
        TenantGuard.check(plan.getTenantId(), tenantId, "Plan");
        return plan;
    }

    /** Resolves a plan by public id ensuring it is usable (active) for the tenant. */
    @Transactional(readOnly = true)
    public MembershipPlan requireActive(String publicId, Long tenantId) {
        MembershipPlan plan = getByPublicId(publicId, tenantId);
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw CommonExceptions.badRequest("Plan is archived and cannot be used");
        }
        return plan;
    }

    @Transactional
    public MembershipPlan create(CreatePlan request, Long tenantId) {
        if (planRepository.existsByTenantIdAndNameIgnoreCase(tenantId, request.name())) {
            throw CommonExceptions.conflict("A plan with this name already exists");
        }
        MembershipPlan plan = new MembershipPlan(tenantId, request.name(), request.description(),
                request.price(), request.currency(), request.durationDays());
        MembershipPlan saved = planRepository.save(plan);
        auditService.record(AuditActions.PLAN_CREATED, AuditActions.RESULT_SUCCESS,
                "MembershipPlan", saved.getPublicId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public MembershipPlan update(String publicId, UpdatePlan request, Long tenantId) {
        MembershipPlan plan = getByPublicId(publicId, tenantId);
        plan.setName(request.name());
        plan.setDescription(request.description());
        plan.setPrice(request.price());
        plan.setCurrency(request.currency());
        plan.setDurationDays(request.durationDays());
        MembershipPlan saved = planRepository.save(plan);
        auditService.record(AuditActions.PLAN_UPDATED, AuditActions.RESULT_SUCCESS,
                "MembershipPlan", saved.getPublicId(), null);
        return saved;
    }

    @Transactional
    public void archive(String publicId, Long tenantId) {
        MembershipPlan plan = getByPublicId(publicId, tenantId);
        plan.setStatus(PlanStatus.ARCHIVED);
        planRepository.save(plan);
        auditService.record(AuditActions.PLAN_ARCHIVED, AuditActions.RESULT_SUCCESS,
                "MembershipPlan", plan.getPublicId(), null);
    }
}
