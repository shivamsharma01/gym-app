package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.tenant.TenantGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A reader list does not create members. People seen on a reader stay in review.
 */
@Service
public class DeviceUserImportService {

    private final DeviceRepository deviceRepository;

    public DeviceUserImportService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public ImportResult importUsers(String devicePublicId, Long tenantId) {
        Device device = deviceRepository.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(device.getTenantId(), tenantId, "Device");
        throw CommonExceptions.badRequest(
                "A reader list does not create members. People seen on a reader stay in review.");
    }

    public record ImportResult(
            int created,
            int mapped,
            int skipped,
            int inactiveFrozen,
            int inferredEndDates,
            int deviceUsersSeen) {
    }
}
