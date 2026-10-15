package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.GatewayRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a sync command to the device's owning gateway over the WSS link. Returns false when the
 * device has no assigned/connected gateway, so the outbox keeps the command for retry rather than
 * pretending it was applied on the device.
 */
@Component
@ConditionalOnProperty(prefix = "app.gateway", name = "simulator-enabled", havingValue = "false",
        matchIfMissing = true)
public class WebSocketGatewayCommandTransport implements GatewayCommandTransport {

    private static final Logger log = LoggerFactory.getLogger(WebSocketGatewayCommandTransport.class);

    private final GatewaySessionRegistry registry;
    private final DeviceRepository deviceRepository;
    private final GatewayRepository gatewayRepository;
    private final JsonMapper jsonMapper;
    private final LogThrottle throttle = new LogThrottle(Duration.ofMinutes(5));

    public WebSocketGatewayCommandTransport(GatewaySessionRegistry registry,
                                            DeviceRepository deviceRepository,
                                            GatewayRepository gatewayRepository,
                                            JsonMapper jsonMapper) {
        this.registry = registry;
        this.deviceRepository = deviceRepository;
        this.gatewayRepository = gatewayRepository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Outcome dispatch(DeviceSyncCommand command) {
        Device device = deviceRepository.findById(command.getDeviceId()).orElse(null);
        if (device == null || device.getGatewayId() == null) {
            if (throttle.allow("no-gateway:" + command.getDeviceId())) {
                log.warn("Command {} {} not delivered: device {} {}. Assign the device to a gateway.",
                        command.getType(), command.getCorrelationId(), command.getDeviceId(),
                        device == null ? "does not exist" : device.getPublicId() + " has no gateway assigned");
            }
            return Outcome.FAILED;
        }
        Gateway gateway = gatewayRepository.findById(device.getGatewayId()).orElse(null);
        if (gateway == null) {
            if (throttle.allow("missing-gateway:" + device.getId())) {
                log.warn("Command {} {} not delivered: device {} is assigned to gateway row {}, which does not exist",
                        command.getType(), command.getCorrelationId(), device.getPublicId(), device.getGatewayId());
            }
            return Outcome.FAILED;
        }
        if (!registry.isOnline(gateway.getPublicId())) {
            if (throttle.allow("offline:" + device.getId())) {
                log.info("Commands for device {} wait: its gateway {} is not connected.",
                        device.getPublicId(), gateway.getPublicId());
            }
            return Outcome.NOT_CONNECTED;
        }

        JsonNode payload = command.getPayload() == null
                ? jsonMapper.createObjectNode()
                : jsonMapper.readTree(command.getPayload());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", java.util.UUID.randomUUID().toString());
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("gatewayId", gateway.getPublicId());
        envelope.put("deviceId", device.getPublicId());
        envelope.put("type", command.getType().name());
        envelope.put("correlationId", command.getCorrelationId());
        envelope.put("payload", payload);

        boolean sent = registry.send(gateway.getPublicId(), jsonMapper.writeValueAsString(envelope));
        if (!sent) {
            log.warn("Sending command {} {} to gateway {} failed; it will be retried",
                    command.getType(), command.getCorrelationId(), gateway.getPublicId());
        }
        return sent ? Outcome.SENT : Outcome.FAILED;
    }
}
