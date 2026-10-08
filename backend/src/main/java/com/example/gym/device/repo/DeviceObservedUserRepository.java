package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceObservedUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceObservedUserRepository extends JpaRepository<DeviceObservedUser, Long> {

    Optional<DeviceObservedUser> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    List<DeviceObservedUser> findByDeviceId(Long deviceId);
}
