package com.example.gym.device.repo;

import com.example.gym.device.domain.AttendanceSyncCursor;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceSyncCursorRepository extends JpaRepository<AttendanceSyncCursor, Long> {

    Optional<AttendanceSyncCursor> findByDeviceId(Long deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AttendanceSyncCursor c where c.deviceId = :deviceId")
    Optional<AttendanceSyncCursor> lockByDeviceId(@Param("deviceId") Long deviceId);
}
