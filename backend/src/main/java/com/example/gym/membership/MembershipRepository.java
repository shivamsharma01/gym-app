package com.example.gym.membership;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByPublicIdAndDeletedFalse(String publicId);

    List<Membership> findByMemberIdAndDeletedFalseOrderByStartDateDesc(Long memberId);

    List<Membership> findByTenantIdAndDeletedFalse(Long tenantId);

    long countByTenantIdAndStatusAndDeletedFalse(
            Long tenantId,
            MembershipStatus status);
}
