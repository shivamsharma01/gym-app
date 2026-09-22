package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.SyncCommandState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceSyncCommandRepository extends JpaRepository<DeviceSyncCommand, Long> {

    Optional<DeviceSyncCommand> findByPublicId(String publicId);

    Optional<DeviceSyncCommand> findByCorrelationId(String correlationId);

    /** Claims commands that are due for (re)dispatch, oldest first. */
    List<DeviceSyncCommand> findByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Collection<SyncCommandState> states, Instant now, Pageable pageable);

    Page<DeviceSyncCommand> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Page<DeviceSyncCommand> findByTenantIdAndDeviceIdOrderByCreatedAtDesc(
            Long tenantId, Long deviceId, Pageable pageable);

    long countByDeviceIdAndStateIn(Long deviceId, Collection<SyncCommandState> states);

    Optional<DeviceSyncCommand> findTopByDeviceIdAndStateOrderByCompletedAtDesc(
            Long deviceId, SyncCommandState state);

    List<DeviceSyncCommand> findByDeviceIdInAndStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Collection<Long> deviceIds, Collection<SyncCommandState> states, Instant now,
            Pageable pageable);

    /** Stale DISPATCHED / ACKNOWLEDGED rows that never received SYNC_RESULT. */
    List<DeviceSyncCommand> findByStateInAndDispatchedAtLessThanEqual(
            Collection<SyncCommandState> states, Instant cutoff, Pageable pageable);

    boolean existsByDeviceIdAndTypeAndStateIn(
            Long deviceId, com.example.gym.device.domain.SyncCommandType type,
            Collection<SyncCommandState> states);
}
