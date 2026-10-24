package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceObservedUser;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceObservedUserRepository extends JpaRepository<DeviceObservedUser, Long> {

    Optional<DeviceObservedUser> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    List<DeviceObservedUser> findByTenantIdAndDeviceUserId(Long tenantId, String deviceUserId);

    List<DeviceObservedUser> findByDeviceId(Long deviceId);

    @Query("""
            select o from DeviceObservedUser o
            where o.tenantId = :tenantId and lower(o.faceSha256) in :hashes
            """)
    List<DeviceObservedUser> findByTenantIdAndFaceSha256In(
            @Param("tenantId") Long tenantId, @Param("hashes") Collection<String> hashes);

    /**
     * What a reader shows for each person staff have not decided on. A locking read: sees an
     * enrollment another transaction committed after this one's snapshot.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select o from DeviceObservedUser o, PendingEnrollment e
            where e.deviceId = :deviceId and e.resolved = false
              and o.deviceId = e.deviceId and o.deviceUserId = e.deviceUserId
            """)
    List<DeviceObservedUser> lockUndecided(@Param("deviceId") Long deviceId);
}
