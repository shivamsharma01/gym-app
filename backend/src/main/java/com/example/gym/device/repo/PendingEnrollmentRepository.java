package com.example.gym.device.repo;

import com.example.gym.device.domain.PendingEnrollment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingEnrollmentRepository extends JpaRepository<PendingEnrollment, Long> {

    Optional<PendingEnrollment> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    List<PendingEnrollment> findByDeviceId(Long deviceId);
}
