package com.example.gym.member;

import java.time.LocalDate;
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

    Optional<Member> findByTenantIdAndSerialNumber(Long tenantId, String serialNumber);

    @Query("select m.serialNumber from Member m where m.tenantId = :tenantId and m.serialNumber is not null")
    List<String> findSerialNumbers(@Param("tenantId") Long tenantId);

    /** Member codes still used as the reader id: members created before serial numbers. */
    @Query("select m.memberCode from Member m where m.tenantId = :tenantId and m.serialNumber is null")
    List<String> findCodesWithoutSerial(@Param("tenantId") Long tenantId);

    /**
     * Tenant-scoped search over name/phone/serial/member-code with optional status and
     * creation-source filters. All filters are optional (pass {@code null} to skip).
     * {@code name} is matched against "first last " (word-separated, ending in a space), so a
     * trailing space ("kunal ") finds Kunal Sharma but not Kunalpreet, and "kunal sharma " still
     * matches; {@code q} is the trimmed text for the other columns.
     */
    @Query("""
            select m from Member m
            where m.tenantId = :tenantId
              and (:status is null or m.status = :status)
              and (:creationSource is null or m.creationSource = :creationSource)
              and (:deviceAuthority is null or m.deviceAuthority = :deviceAuthority)
              and (:q is null
                   or lower(concat(m.firstName, ' ', coalesce(m.lastName, ''), ' ')) like lower(concat('%', :name, '%'))
                   or lower(m.memberCode) like lower(concat('%', :q, '%'))
                   or lower(m.serialNumber) like lower(concat('%', :q, '%'))
                   or m.phone like concat('%', :q, '%'))
            """)
    Page<Member> search(@Param("tenantId") Long tenantId,
                        @Param("q") String q,
                        @Param("name") String name,
                        @Param("status") MemberStatus status,
                        @Param("creationSource") MemberCreationSource creationSource,
                        @Param("deviceAuthority") DeviceAuthority deviceAuthority,
                        Pageable pageable);

    long countByTenantId(Long tenantId);

    List<Member> findByTenantIdAndStatus(Long tenantId, MemberStatus status);

    long countByTenantIdAndStatus(Long tenantId, MemberStatus status);

    List<Member> findByTenantIdAndJoinedOnBetweenOrderByJoinedOnDesc(
            Long tenantId, LocalDate from, LocalDate to);
}
