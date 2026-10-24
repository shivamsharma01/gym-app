package com.example.gym.member;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPublicId(String publicId);

    boolean existsByTenantIdAndMemberCode(Long tenantId, String memberCode);

    Optional<Member> findByTenantIdAndMemberCode(Long tenantId, String memberCode);

    /**
     * Tenant-scoped search over name, phone, and member code, with optional status and device
     * authority filters. All filters are optional (pass {@code null} to skip).
     * {@code name} is matched against "first last " (word-separated, ending in a space), so a
     * trailing space ("kunal ") finds Kunal Sharma but not Kunalpreet, and "kunal sharma " still
     * matches; {@code q} is the trimmed text for the other columns. {@code readerMemberIds} is
     * never empty: pass a single {@code -1} when the text is not a reader id.
     */
    @Query("""
            select m from Member m
            where m.tenantId = :tenantId
              and (:status is null or m.status = :status)
              and (:deviceAuthority is null or m.deviceAuthority = :deviceAuthority)
              and (:q is null
                   or lower(concat(m.firstName, ' ', coalesce(m.lastName, ''), ' ')) like lower(concat('%', :name, '%'))
                   or lower(m.memberCode) like lower(concat('%', :q, '%'))
                   or m.phone like concat('%', :q, '%')
                   or m.id in :readerMemberIds)
            """)
    Page<Member> search(@Param("tenantId") Long tenantId,
                        @Param("q") String q,
                        @Param("name") String name,
                        @Param("status") MemberStatus status,
                        @Param("deviceAuthority") DeviceAuthority deviceAuthority,
                        @Param("readerMemberIds") Collection<Long> readerMemberIds,
                        Pageable pageable);

    long countByTenantId(Long tenantId);

    List<Member> findByTenantIdAndStatus(Long tenantId, MemberStatus status);

    List<Member> findByTenantId(Long tenantId);

    @Query("""
            select m from Member m, MemberFace f
            where f.memberId = m.id and m.tenantId = :tenantId and f.sha256 = :sha256
            """)
    List<Member> findByTenantIdAndFaceSha256(@Param("tenantId") Long tenantId, @Param("sha256") String sha256);

    long countByTenantIdAndStatus(Long tenantId, MemberStatus status);

    List<Member> findByTenantIdAndJoinedOnBetweenOrderByJoinedOnDesc(
            Long tenantId, LocalDate from, LocalDate to);
}
