package com.example.gym.membership;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByPublicIdAndDeletedFalse(String publicId);

    List<Membership> findByMemberIdAndDeletedFalseOrderByStartDateDesc(Long memberId);

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
