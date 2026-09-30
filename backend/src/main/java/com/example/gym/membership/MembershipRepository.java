package com.example.gym.membership;

import com.example.gym.membership.dto.PlanActivityCount;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByPublicIdAndDeletedFalse(String publicId);

    List<Membership> findByMemberIdAndDeletedFalseOrderByStartDateDesc(Long memberId);

    List<Membership> findByMemberIdInAndDeletedFalse(Collection<Long> memberIds);

    List<Membership> findByDeletedFalseAndStatusInAndEndDateBefore(
            Collection<MembershipStatus> statuses, LocalDate today);

    List<Membership> findByTenantIdAndDeletedFalse(Long tenantId);

    long countByTenantIdAndStatusAndDeletedFalse(
            Long tenantId,
            MembershipStatus status);

    boolean existsByMemberIdAndDeletedFalseAndStatusNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long memberId,
            MembershipStatus status,
            LocalDate endDate,
            LocalDate startDate
    );

    @Query("""
            SELECT new com.example.gym.membership.dto.PlanActivityCount(
                m.planId,
                COUNT(DISTINCT m.memberId),
                SUM(CASE WHEN m.endDate <= :expiringThrough THEN 1 ELSE 0 END))
            FROM Membership m
            WHERE m.tenantId = :tenantId
              AND m.deleted = false
              AND m.planId IS NOT NULL
              AND m.status IN :openStatuses
              AND m.startDate <= :today
              AND m.endDate >= :today
            GROUP BY m.planId
            """)
    List<PlanActivityCount> countActiveByPlan(
            @Param("tenantId") Long tenantId,
            @Param("openStatuses") Collection<MembershipStatus> openStatuses,
            @Param("today") LocalDate today,
            @Param("expiringThrough") LocalDate expiringThrough);

    @Query("""
            SELECT m FROM Membership m
            WHERE m.tenantId = :tenantId
              AND m.planId = :planId
              AND m.deleted = false
              AND m.status IN :openStatuses
              AND m.startDate <= :today
              AND m.endDate >= :today
            """)
    Page<Membership> findCoveringPlan(
            @Param("tenantId") Long tenantId,
            @Param("planId") Long planId,
            @Param("openStatuses") Collection<MembershipStatus> openStatuses,
            @Param("today") LocalDate today,
            Pageable pageable);

    @Query("""
            SELECT COUNT(m) > 0
            FROM Membership m
            WHERE m.memberId = :memberId
              AND m.deleted = false
              AND m.status <> com.example.gym.membership.MembershipStatus.CANCELLED
              AND m.publicId <> :membershipPublicId
              AND m.startDate <= :endDate
              AND m.endDate >= :startDate
            """)
    boolean existsOverlappingMembership(
            @Param("memberId") Long memberId,
            @Param("membershipPublicId") String membershipPublicId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
