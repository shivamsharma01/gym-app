package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * When a gateway registers: backfill member mappings onto its devices, enqueue reconcile, release
 * the commands that waited while it was offline and send them. The registration is published
 * outside a transaction, so {@code fallbackExecution} is required or Spring drops the event.
 */
@Component
public class GatewayConnectedListener {

    private static final Logger log = LoggerFactory.getLogger(GatewayConnectedListener.class);

    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final DeviceSyncService deviceSyncService;
    private final MemberDeviceProvisioningService provisioning;

    public GatewayConnectedListener(DeviceRepository deviceRepository,
                                    DeviceService deviceService,
                                    DeviceSyncService deviceSyncService,
                                    MemberDeviceProvisioningService provisioning) {
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.deviceSyncService = deviceSyncService;
        this.provisioning = provisioning;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGatewayConnected(GatewayConnectedEvent event) {
        try {
            List<Device> devices = deviceRepository.findByGatewayId(event.gatewayInternalId());
            for (Device device : devices) {
                provisioning.provisionDevice(device);
                deviceService.enqueueReconcileIfAbsent(device);
            }
            int woken = deviceSyncService.wakeGateway(devices.stream().map(Device::getId).toList());
            int dispatched = deviceSyncService.dispatchDue();
            log.info("Gateway {} connected: reconcile queued for {} device(s), {} waiting command(s) released, "
                    + "dispatched {}", event.gatewayPublicId(), devices.size(), woken, dispatched);
        } catch (RuntimeException ex) {
            // The regular dispatcher and reconcile schedule still catch up; don't fail the registration.
            log.error("Post-connect sync for gateway {} failed", event.gatewayPublicId(), ex);
        }
    }
}
