package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceReaderBaseline;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceReaderBaselineRepository extends JpaRepository<DeviceReaderBaseline, Long> {

    Optional<DeviceReaderBaseline> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    List<DeviceReaderBaseline> findByDeviceId(Long deviceId);
}
