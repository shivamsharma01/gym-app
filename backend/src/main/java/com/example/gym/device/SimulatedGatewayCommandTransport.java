package com.example.gym.device;

import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.SyncCommandType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process stand-in for a LAN gateway. Applies commands to an in-memory mock device and, after
 * the outbox transaction commits, reports a {@code SYNC_RESULT}. Face enrolment is never reported
 * as {@code ENROLLED} — remote enrolment is UNVERIFIED on the real hardware, so the simulator
 * returns {@code GUIDED_PENDING} instead of faking biometric success.
 */
@Component
@ConditionalOnProperty(prefix = "app.gateway", name = "simulator-enabled", havingValue = "true")
public class SimulatedGatewayCommandTransport implements GatewayCommandTransport {

    private static final Logger log = LoggerFactory.getLogger(SimulatedGatewayCommandTransport.class);

    private final ApplicationEventPublisher events;

    public SimulatedGatewayCommandTransport(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public boolean dispatch(DeviceSyncCommand command) {
        boolean enroll = command.getType() == SyncCommandType.ENROLL_FACE;
        log.info("Simulator applied {} correlationId={}", command.getType(), command.getCorrelationId());
        events.publishEvent(new SimulatedSyncCompleted(command.getCorrelationId(), !enroll,
                enroll ? "UNVERIFIED: remote face enrollment is not simulated as success" : null));
        return true;
    }

    public record SimulatedSyncCompleted(String correlationId, boolean ok, String error) {
    }

    @Component
    @ConditionalOnProperty(prefix = "app.gateway", name = "simulator-enabled", havingValue = "true")
    static class ResultListener {
        private final DeviceSyncService deviceSyncService;

        ResultListener(DeviceSyncService deviceSyncService) {
            this.deviceSyncService = deviceSyncService;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onCompleted(SimulatedSyncCompleted event) {
            deviceSyncService.handleResult(event.correlationId(), event.ok(), event.error());
        }
    }
}
