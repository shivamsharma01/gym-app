package com.example.gym.membership;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByPublicId(String publicId);

    List<Membership> findByMemberIdOrderByStartDateDesc(Long memberId);

    List<Membership> findByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, MembershipStatus status);
}
