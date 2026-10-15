package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceReviewSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceReviewSnapshotRepository extends JpaRepository<DeviceReviewSnapshot, Long> {

    Optional<DeviceReviewSnapshot> findByReviewItemIdAndDeviceId(Long reviewItemId, Long deviceId);

    List<DeviceReviewSnapshot> findByReviewItemId(Long reviewItemId);
}
