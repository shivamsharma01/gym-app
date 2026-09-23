package com.example.gym.plan;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {

    Optional<MembershipPlan> findByPublicId(String publicId);

    Page<MembershipPlan> findByTenantId(Long tenantId, Pageable pageable);

    Page<MembershipPlan> findByTenantIdAndStatus(Long tenantId, PlanStatus status, Pageable pageable);

    boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    Optional<MembershipPlan> findFirstByTenantIdAndNameIgnoreCase(Long tenantId, String name);
}
