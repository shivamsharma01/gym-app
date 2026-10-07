package com.example.gym.device.repo;

import com.example.gym.device.domain.DesiredMemberProjection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesiredMemberProjectionRepository extends JpaRepository<DesiredMemberProjection, Long> {

    List<DesiredMemberProjection> findByDeviceId(Long deviceId);

    Optional<DesiredMemberProjection> findByDeviceIdAndMemberId(Long deviceId, Long memberId);

    Optional<DesiredMemberProjection> findByDeviceIdAndRevision(Long deviceId, Long revision);

    List<DesiredMemberProjection> findByDeviceIdAndRevisionGreaterThanOrderByRevisionAsc(
            Long deviceId, long revision, Pageable pageable);
}
