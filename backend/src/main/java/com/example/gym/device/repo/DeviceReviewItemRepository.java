package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceReviewItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceReviewItemRepository extends JpaRepository<DeviceReviewItem, Long> {

    Optional<DeviceReviewItem> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    List<DeviceReviewItem> findByDeviceId(Long deviceId);

    List<DeviceReviewItem> findByMemberId(Long memberId);
}
