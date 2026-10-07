package com.example.gym.device.repo;

import com.example.gym.device.domain.ReaderBlockedUser;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReaderBlockedUserRepository extends JpaRepository<ReaderBlockedUser, Long> {

    List<ReaderBlockedUser> findByDeviceId(Long deviceId);

    boolean existsByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);
}
