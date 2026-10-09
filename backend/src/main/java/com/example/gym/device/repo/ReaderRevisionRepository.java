package com.example.gym.device.repo;

import com.example.gym.device.domain.ReaderRevision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReaderRevisionRepository extends JpaRepository<ReaderRevision, Long> {

    Optional<ReaderRevision> findByDeviceId(Long deviceId);
}
