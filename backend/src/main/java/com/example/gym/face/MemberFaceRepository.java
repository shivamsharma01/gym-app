package com.example.gym.face;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberFaceRepository extends JpaRepository<MemberFace, Long> {

    Optional<MemberFace> findByMemberId(Long memberId);

    List<MemberFace> findByMemberIdIn(Collection<Long> memberIds);

    @Query("select f from MemberFace f where f.tenantId = :tenantId and lower(f.sha256) = lower(:sha256)")
    List<MemberFace> findByTenantIdAndSha256(@Param("tenantId") Long tenantId, @Param("sha256") String sha256);
}
