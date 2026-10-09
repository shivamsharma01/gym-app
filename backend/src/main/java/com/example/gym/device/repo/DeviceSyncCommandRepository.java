package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.SyncCommandState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface DeviceSyncCommandRepository extends JpaRepository<DeviceSyncCommand, Long> {

    Optional<DeviceSyncCommand> findByPublicId(String publicId);

    Optional<DeviceSyncCommand> findByCorrelationId(String correlationId);

    /** Claims commands that are due for (re)dispatch, oldest first. */
    Page<DeviceSyncCommand> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Page<DeviceSyncCommand> findByTenantIdAndDeviceIdOrderByCreatedAtDesc(
            Long tenantId, Long deviceId, Pageable pageable);

    Page<DeviceSyncCommand> findByTenantIdAndStateInOrderByCreatedAtDesc(
            Long tenantId, Collection<SyncCommandState> states, Pageable pageable);

    Page<DeviceSyncCommand> findByTenantIdAndDeviceIdAndStateInOrderByCreatedAtDesc(
            Long tenantId, Long deviceId, Collection<SyncCommandState> states, Pageable pageable);

    long countByDeviceIdAndStateIn(Long deviceId, Collection<SyncCommandState> states);

    Optional<DeviceSyncCommand> findTopByDeviceIdAndStateOrderByCompletedAtDesc(
            Long deviceId, SyncCommandState state);

    /**
     * Due commands, row-locked for the calling transaction. Rows another dispatcher has already locked are
     * skipped, so two concurrent dispatches (connect and the outbox timer) never send the same command.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select c from DeviceSyncCommand c where c.deviceId in :deviceIds and c.state in :states "
            + "and c.nextAttemptAt <= :now order by c.nextAttemptAt asc")
    List<DeviceSyncCommand> claimDue(@Param("deviceIds") Collection<Long> deviceIds,
                                     @Param("states") Collection<SyncCommandState> states,
                                     @Param("now") Instant now,
                                     Pageable pageable);

    /** Stale DISPATCHED / ACKNOWLEDGED rows that never received SYNC_RESULT. */
    List<DeviceSyncCommand> findByStateInAndDispatchedAtLessThanEqual(
            Collection<SyncCommandState> states, Instant cutoff, Pageable pageable);

    boolean existsByDeviceIdAndTypeAndStateIn(
            Long deviceId, com.example.gym.device.domain.SyncCommandType type,
            Collection<SyncCommandState> states);

    List<DeviceSyncCommand> findByDeviceIdAndMemberIdAndTypeInAndStateIn(
            Long deviceId, Long memberId,
            Collection<com.example.gym.device.domain.SyncCommandType> types,
            Collection<SyncCommandState> states);

    List<DeviceSyncCommand> findByMemberIdAndStateIn(Long memberId, Collection<SyncCommandState> states);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DeviceSyncCommand c set c.nextAttemptAt = :now "
            + "where c.deviceId in :deviceIds and c.state in :states and c.nextAttemptAt > :now")
    int makeDueNow(@org.springframework.data.repository.query.Param("deviceIds") Collection<Long> deviceIds,
                   @org.springframework.data.repository.query.Param("states") Collection<SyncCommandState> states,
                   @org.springframework.data.repository.query.Param("now") Instant now);
}
