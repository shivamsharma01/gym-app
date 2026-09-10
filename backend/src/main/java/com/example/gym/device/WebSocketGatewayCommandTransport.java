package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.GatewayRepository;
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
    public boolean dispatch(DeviceSyncCommand command) {
        Device device = deviceRepository.findById(command.getDeviceId()).orElse(null);
        if (device == null || device.getGatewayId() == null) {
            return false;
        }
        Gateway gateway = gatewayRepository.findById(device.getGatewayId()).orElse(null);
        if (gateway == null) {
            return false;
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
            log.debug("Gateway {} not connected; command {} will be retried",
                    gateway.getPublicId(), command.getCorrelationId());
        }
        return sent;
    }
}
