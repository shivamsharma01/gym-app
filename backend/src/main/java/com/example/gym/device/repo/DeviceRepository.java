package com.example.gym.device.repo;

import com.example.gym.device.domain.Device;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByPublicId(String publicId);

    Page<Device> findByTenantId(Long tenantId, Pageable pageable);

    List<Device> findByTenantId(Long tenantId);

    List<Device> findByGatewayId(Long gatewayId);
}
