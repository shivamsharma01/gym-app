package com.example.gym.device.repo;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.AccessResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    Optional<AttendanceEvent> findByPublicId(String publicId);

    boolean existsByTenantIdAndDeviceIdAndFingerprint(Long tenantId, Long deviceId, String fingerprint);

    Optional<AttendanceEvent> findByTenantIdAndDeviceIdAndFingerprint(
            Long tenantId, Long deviceId, String fingerprint);

    Optional<AttendanceEvent> findFirstByTenantIdAndDeviceIdAndDeviceUserIdAndOccurredAtAndMethodAndResultAndDeviceRecNoIsNull(
            Long tenantId, Long deviceId, String deviceUserId, Instant occurredAt, String method,
            AccessResult result);

    Page<AttendanceEvent> findByTenantIdOrderByOccurredAtDesc(Long tenantId, Pageable pageable);

    Page<AttendanceEvent> findByTenantIdAndMemberIdOrderByOccurredAtDesc(
            Long tenantId, Long memberId, Pageable pageable);

    long countByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long tenantId, Instant from, Instant to);

    long countByTenantIdAndResultAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long tenantId, AccessResult result, Instant from, Instant to);

    List<AttendanceEvent> findByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDesc(
            Long tenantId, Instant from, Instant to);

    /** Credits events stored before this reader's user was mapped. Linked rows are never touched. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update AttendanceEvent e set e.memberId = :memberId
            where e.tenantId = :tenantId and e.deviceId = :deviceId
              and e.deviceUserId = :deviceUserId and e.memberId is null""")
    int linkUnmatched(@Param("tenantId") Long tenantId, @Param("deviceId") Long deviceId,
                      @Param("deviceUserId") String deviceUserId, @Param("memberId") Long memberId);
}
