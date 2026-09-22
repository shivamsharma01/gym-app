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
 * After gateway registration commits: enqueue reconcile and flush pending outbox commands.
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGatewayConnected(GatewayConnectedEvent event) {
        List<Device> devices = deviceRepository.findByGatewayId(event.gatewayInternalId());
        for (Device device : devices) {
            deviceService.enqueueReconcileIfAbsent(device);
        }
        int dispatched = deviceSyncService.dispatchDue();
        log.info("Gateway {} connected: reconcile queued for {} device(s), dispatched {}",
                event.gatewayPublicId(), devices.size(), dispatched);
    }
}
