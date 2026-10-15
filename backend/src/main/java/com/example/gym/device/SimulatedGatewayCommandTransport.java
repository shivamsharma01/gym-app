package com.example.gym.device;

import com.example.gym.device.domain.DeviceSyncCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process stand-in for a LAN gateway. After the outbox transaction commits it reports a
 * successful {@code SYNC_RESULT} for each command.
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
    public Outcome dispatch(DeviceSyncCommand command) {
        log.info("Simulator applied {} correlationId={}", command.getType(), command.getCorrelationId());
        events.publishEvent(new SimulatedSyncCompleted(command.getCorrelationId(), true, null));
        return Outcome.SENT;
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
