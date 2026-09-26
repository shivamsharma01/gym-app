package com.example.gym.device.repo;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.AccessResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
