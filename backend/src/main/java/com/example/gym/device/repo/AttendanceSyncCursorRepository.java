package com.example.gym.device.repo;

import com.example.gym.device.domain.AttendanceSyncCursor;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceSyncCursorRepository extends JpaRepository<AttendanceSyncCursor, Long> {

    Optional<AttendanceSyncCursor> findByDeviceId(Long deviceId);
}
