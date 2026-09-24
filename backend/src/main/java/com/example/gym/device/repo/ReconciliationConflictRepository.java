package com.example.gym.device.repo;

import com.example.gym.device.domain.ReconciliationConflict;
import com.example.gym.device.domain.ReconciliationConflictStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationConflictRepository extends JpaRepository<ReconciliationConflict, Long> {

    Optional<ReconciliationConflict> findByPublicId(String publicId);

    long countByDeviceIdAndStatus(Long deviceId, ReconciliationConflictStatus status);

    Page<ReconciliationConflict> findByTenantIdAndDeviceIdAndStatusOrderByCreatedAtDesc(
            Long tenantId, Long deviceId, ReconciliationConflictStatus status, Pageable pageable);

    List<ReconciliationConflict> findByDeviceIdAndStatusAndDeviceUserId(
            Long deviceId, ReconciliationConflictStatus status, String deviceUserId);

    Optional<ReconciliationConflict> findFirstByDeviceIdAndDeviceUserIdAndConflictTypeAndStatus(
            Long deviceId, String deviceUserId,
            com.example.gym.device.domain.ReconciliationConflictType conflictType,
            ReconciliationConflictStatus status);
}
