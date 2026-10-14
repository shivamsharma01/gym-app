package com.example.gym.device.repo;

import com.example.gym.device.domain.DesiredMemberProjection;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DesiredMemberProjectionRepository extends JpaRepository<DesiredMemberProjection, Long> {

    List<DesiredMemberProjection> findByDeviceId(Long deviceId);

    List<DesiredMemberProjection> findByMemberId(Long memberId);

    Optional<DesiredMemberProjection> findByDeviceIdAndMemberId(Long deviceId, Long memberId);

    /** A locking read: sees a row another transaction committed after this one's snapshot. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DesiredMemberProjection p where p.deviceId = :deviceId and p.memberId = :memberId")
    Optional<DesiredMemberProjection> lockByDeviceIdAndMemberId(@Param("deviceId") Long deviceId,
                                                                @Param("memberId") Long memberId);

    Optional<DesiredMemberProjection> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    Optional<DesiredMemberProjection> findByDeviceIdAndRevision(Long deviceId, Long revision);

    List<DesiredMemberProjection> findByDeviceIdAndRevisionGreaterThanOrderByRevisionAsc(
            Long deviceId, long revision, Pageable pageable);
}
