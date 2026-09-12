package com.example.gym.member;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPublicId(String publicId);

    boolean existsByTenantIdAndMemberCode(Long tenantId, String memberCode);

    /**
     * Tenant-scoped search over name/phone/member-code with an optional status filter. All filters
     * are optional (pass {@code null} to skip). Case-insensitive substring match on the query.
     */
    @Query("""
            select m from Member m
            where m.tenantId = :tenantId
              and (:status is null or m.status = :status)
              and (:q is null or :q = ''
                   or lower(m.firstName) like lower(concat('%', :q, '%'))
                   or lower(m.lastName) like lower(concat('%', :q, '%'))
                   or lower(m.memberCode) like lower(concat('%', :q, '%'))
                   or m.phone like concat('%', :q, '%'))
            """)
    Page<Member> search(@Param("tenantId") Long tenantId,
                        @Param("q") String q,
                        @Param("status") MemberStatus status,
                        Pageable pageable);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, MemberStatus status);
}
