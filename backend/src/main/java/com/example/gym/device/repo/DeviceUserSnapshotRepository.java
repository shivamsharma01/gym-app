package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceUserSnapshotRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceUserSnapshotRepository extends JpaRepository<DeviceUserSnapshotRow, Long> {

    List<DeviceUserSnapshotRow> findByDeviceId(Long deviceId);

    List<DeviceUserSnapshotRow> findByTenantId(Long tenantId);

    @Modifying(clearAutomatically = true)
    @Query("delete from DeviceUserSnapshotRow s where s.deviceId = :deviceId")
    void deleteByDeviceId(@Param("deviceId") Long deviceId);
}
