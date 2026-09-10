package com.example.gym.device.repo;

import com.example.gym.device.domain.AttendanceEvent;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    Optional<AttendanceEvent> findByPublicId(String publicId);

    boolean existsByTenantIdAndDeviceIdAndFingerprint(Long tenantId, Long deviceId, String fingerprint);

    Page<AttendanceEvent> findByTenantIdOrderByOccurredAtDesc(Long tenantId, Pageable pageable);

    Page<AttendanceEvent> findByTenantIdAndMemberIdOrderByOccurredAtDesc(
            Long tenantId, Long memberId, Pageable pageable);
}
