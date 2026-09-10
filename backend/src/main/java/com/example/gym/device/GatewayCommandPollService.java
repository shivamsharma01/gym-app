package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.GatewayRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST fallback for gateways that cannot (yet) hold a WSS session — used by the Python simulator
 * and as a reconnect-safe ingest path. Claiming a command marks it DISPATCHED so the WSS
 * dispatcher does not also deliver it.
 */
@Service
public class GatewayCommandPollService {

    private final GatewayRepository gatewayRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceSyncCommandRepository commandRepository;
    private final GatewayProperties properties;
    private final JsonMapper jsonMapper;

    public GatewayCommandPollService(GatewayRepository gatewayRepository,
                                     DeviceRepository deviceRepository,
                                     DeviceSyncCommandRepository commandRepository,
                                     GatewayProperties properties,
                                     JsonMapper jsonMapper) {
        this.gatewayRepository = gatewayRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public List<Map<String, Object>> claimDue(Gateway gateway) {
        List<Device> devices = deviceRepository.findByGatewayId(gateway.getId());
        if (devices.isEmpty()) {
            return List.of();
        }
        List<Long> deviceIds = devices.stream().map(Device::getId).toList();
        List<DeviceSyncCommand> due = commandRepository
                .findByDeviceIdInAndStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        deviceIds,
                        List.of(SyncCommandState.PENDING, SyncCommandState.RETRYING),
                        Instant.now(),
                        PageRequest.of(0, properties.getOutbox().getBatchSize()));

        List<Map<String, Object>> envelopes = new ArrayList<>();
        Instant now = Instant.now();
        for (DeviceSyncCommand command : due) {
            Device device = devices.stream()
                    .filter(d -> d.getId().equals(command.getDeviceId()))
                    .findFirst()
                    .orElse(null);
            if (device == null) {
                continue;
            }
            command.setState(SyncCommandState.DISPATCHED);
            command.setDispatchedAt(now);
            command.setAttemptCount(command.getAttemptCount() + 1);
            commandRepository.save(command);
            envelopes.add(envelope(gateway, device, command));
        }
        return envelopes;
    }

    public Gateway requireGateway(String publicId) {
        return gatewayRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Gateway"));
    }

    private Map<String, Object> envelope(Gateway gateway, Device device, DeviceSyncCommand command) {
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
        return envelope;
    }
}
