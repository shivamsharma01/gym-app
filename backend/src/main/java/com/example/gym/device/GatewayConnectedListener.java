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
 * When a gateway registers: queue an attendance reconcile, release waiting door and clock
 * commands, and send them. Member state is not copied onto the readers here.
 */
@Component
public class GatewayConnectedListener {

    private static final Logger log = LoggerFactory.getLogger(GatewayConnectedListener.class);

    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final DeviceSyncService deviceSyncService;

    public GatewayConnectedListener(DeviceRepository deviceRepository,
                                    DeviceService deviceService,
                                    DeviceSyncService deviceSyncService) {
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.deviceSyncService = deviceSyncService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGatewayConnected(GatewayConnectedEvent event) {
        try {
            List<Device> devices = deviceRepository.findByGatewayId(event.gatewayInternalId());
            for (Device device : devices) {
                deviceService.enqueueReconcileIfAbsent(device);
            }
            int woken = deviceSyncService.wakeGateway(devices.stream().map(Device::getId).toList());
            int dispatched = deviceSyncService.dispatchDue();
            log.info("Gateway {} connected: attendance reconcile queued for {} reader(s), {} waiting command(s) released, "
                    + "dispatched {}", event.gatewayPublicId(), devices.size(), woken, dispatched);
        } catch (RuntimeException ex) {
            log.error("Post-connect sync for gateway {} failed", event.gatewayPublicId(), ex);
        }
    }
}
