package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.EnrollmentStatus;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SecurityEvent;
import com.example.gym.device.protocol.GatewayMessage;
import com.example.gym.device.protocol.GatewayMessageType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.SecurityEventRepository;
import com.example.gym.live.StaffLiveBroadcast;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes inbound gateway messages (§37). Idempotent and defensive: unknown/replayed messages are
 * ignored rather than throwing, and handler errors are returned as an ERROR reply so the gateway
 * link stays healthy.
 */
@Service
public class GatewayMessageService {

    private static final Logger log = LoggerFactory.getLogger(GatewayMessageService.class);

    private final GatewayService gatewayService;
    private final GatewayRepository gatewayRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final AttendanceIngestionService attendanceIngestionService;
    private final DeviceSyncService deviceSyncService;
    private final DeviceReconciliationService reconciliationService;
    private final GatewayMessageDedupeService dedupeService;
    private final SecurityEventRepository securityEventRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;

    public GatewayMessageService(GatewayService gatewayService,
                                 GatewayRepository gatewayRepository,
                                 DeviceRepository deviceRepository,
                                 DeviceService deviceService,
                                 AttendanceIngestionService attendanceIngestionService,
                                 DeviceSyncService deviceSyncService,
                                 DeviceReconciliationService reconciliationService,
                                 GatewayMessageDedupeService dedupeService,
                                 SecurityEventRepository securityEventRepository,
                                 MemberDeviceMappingRepository mappingRepository,
                                 JsonMapper jsonMapper,
                                 ApplicationEventPublisher events) {
        this.gatewayService = gatewayService;
        this.gatewayRepository = gatewayRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.attendanceIngestionService = attendanceIngestionService;
        this.deviceSyncService = deviceSyncService;
        this.reconciliationService = reconciliationService;
        this.dedupeService = dedupeService;
        this.securityEventRepository = securityEventRepository;
        this.mappingRepository = mappingRepository;
        this.jsonMapper = jsonMapper;
        this.events = events;
    }

    public Optional<String> process(String raw) {
        return process(raw, null);
    }

    public Optional<String> process(String raw, String boundGatewayId) {
        GatewayMessage message;
        try {
            message = jsonMapper.readValue(raw, GatewayMessage.class);
        } catch (RuntimeException ex) {
            log.warn("Rejecting malformed gateway message: {}", ex.getMessage());
            return Optional.of(reply(GatewayMessageType.ERROR, null, Map.of("error", "malformed message")));
        }
        if (boundGatewayId != null && message.gatewayId() != null
                && !boundGatewayId.equals(message.gatewayId())) {
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "gateway identity mismatch")));
        }
        if (!dedupeService.claim(message.messageId(), message.gatewayId())) {
            log.debug("Ignoring duplicate gateway messageId {}", message.messageId());
            return ack(message);
        }
        try {
            return handle(message);
        } catch (RuntimeException ex) {
            log.error("Error handling gateway message {} ({})", message.type(), message.messageId(), ex);
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "processing failed")));
        }
    }

    private Optional<String> handle(GatewayMessage message) {
        GatewayMessageType type;
        try {
            type = GatewayMessageType.valueOf(message.type());
        } catch (IllegalArgumentException ex) {
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "unknown type: " + message.type())));
        }

        return switch (type) {
            case REGISTER_GATEWAY -> {
                Gateway gateway = gatewayService.markRegistered(
                        message.gatewayId(), text(message.payload(), "agentVersion"));
                events.publishEvent(new GatewayConnectedEvent(gateway.getPublicId(), gateway.getId()));
                yield Optional.of(reply(GatewayMessageType.REGISTERED, message.correlationId(),
                        Map.of("messageId", message.messageId() == null ? "" : message.messageId())));
            }
            case HEARTBEAT -> {
                gatewayService.recordHeartbeat(message.gatewayId());
                yield ack(message);
            }
            case DEVICE_STATUS, DEVICE_METADATA -> {
                resolveDevice(message).ifPresent(device -> {
                    DeviceConnectionState previous = device.getConnectionState();
                    deviceService.updateConnection(device,
                            connectionState(message.payload()),
                            text(message.payload(), "firmware"),
                            text(message.payload(), "model"),
                            instant(message.payload(), "lastSeen"));
                    DeviceConnectionState state = connectionState(message.payload());
                    events.publishEvent(new StaffLiveBroadcast(device.getTenantId(), "DEVICE_STATUS",
                            Map.of(
                                    "deviceId", device.getPublicId(),
                                    "connectionState", state == null ? "" : state.name())));
                    if (state == DeviceConnectionState.ONLINE
                            && previous != DeviceConnectionState.ONLINE) {
                        deviceService.enqueueReconcileIfAbsent(device);
                    }
                    String details = text(message.payload(), "details");
                    if (details != null && details.toLowerCase().contains("reconnect")) {
                        deviceService.enqueueReconcileIfAbsent(device);
                    }
                });
                yield ack(message);
            }
            case DEVICE_EVENT -> {
                resolveDevice(message).ifPresent(device -> ingestEvent(device, message.payload(),
                        message.timestamp()));
                yield ack(message);
            }
            case DEVICE_ALARM -> {
                resolveDevice(message).ifPresent(device -> {
                    securityEventRepository.save(new SecurityEvent(
                            device.getTenantId(), device.getId(),
                            textOr(message.payload(), "type", "DEVICE_ALARM"),
                            instantOr(message.payload(), "occurredAt", message.timestamp()),
                            text(message.payload(), "details")));
                    events.publishEvent(new StaffLiveBroadcast(device.getTenantId(), "SECURITY_ALARM",
                            Map.of(
                                    "deviceId", device.getPublicId(),
                                    "type", textOr(message.payload(), "type", "DEVICE_ALARM"))));
                });
                yield ack(message);
            }
            case SYNC_RESULT -> {
                deviceSyncService.handleResult(message.correlationId(),
                        boolAt(message.payload(), "ok"), text(message.payload(), "error"));
                yield ack(message);
            }
            case RECONCILIATION_RESULT -> {
                resolveDevice(message).ifPresent(device -> {
                    boolean ok = message.payload() == null || !message.payload().has("ok")
                            || message.payload().get("ok").asBoolean();
                    if (!ok) {
                        attendanceIngestionService.markReconciliationRequired(
                                device.getTenantId(), device.getId(), true);
                    } else {
                        JsonNode eventNodes = message.payload() == null ? null
                                : message.payload().get("events");
                        if (eventNodes != null && eventNodes.isArray()) {
                            eventNodes.forEach(e -> ingestEvent(device, e, message.timestamp()));
                        }
                        reconciliationService.applyDeviceUserSnapshot(device, message.payload());
                        attendanceIngestionService.markReconciliationRequired(
                                device.getTenantId(), device.getId(), false);
                    }
                    // Complete the outbox command even when the gateway also sent SYNC_RESULT
                    if (message.correlationId() != null) {
                        deviceSyncService.handleResult(message.correlationId(), ok,
                                text(message.payload(), "error"));
                    }
                });
                yield ack(message);
            }
            case ENROLLMENT_RESULT -> {
                resolveDevice(message).ifPresent(device -> applyEnrollmentResult(device, message.payload()));
                yield ack(message);
            }
            default -> Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "unsupported inbound type: " + type)));
        };
    }

    String impersonationError() {
        return reply(GatewayMessageType.ERROR, null, Map.of("error", "gateway identity mismatch"));
    }

    private void ingestEvent(Device device, JsonNode payload, Instant messageTimestamp) {
        if (payload == null) {
            return;
        }
        String deviceUserId = text(payload, "deviceUserId");
        Instant occurredAt = instantOr(payload, "occurredAt", messageTimestamp);
        String method = textOr(payload, "method", "UNKNOWN");
        boolean granted = payload.has("granted") ? payload.get("granted").asBoolean()
                : "GRANTED".equalsIgnoreCase(text(payload, "result"));
        Long recNo = null;
        if (payload.has("recNo") && !payload.get("recNo").isNull()) {
            recNo = payload.get("recNo").asLong();
        }
        String denyReason = text(payload, "denyReason");
        if (denyReason == null && payload.has("errorCode") && !payload.get("errorCode").isNull()) {
            denyReason = mapErrorCode(payload.get("errorCode").asInt());
        }
        attendanceIngestionService.ingest(device, deviceUserId, occurredAt, method, granted, recNo,
                denyReason);
    }

    private static String mapErrorCode(int code) {
        return switch (code) {
            case 0x00 -> null;
            case 0x10 -> "UNAUTHORIZED";
            case 0x14 -> "VALIDITY_PERIOD";
            case 0x20, 0x21 -> "PERIOD_ERROR";
            case 0x23 -> "OVERDUE";
            default -> "ERR_0x" + Integer.toHexString(code).toUpperCase();
        };
    }

    private void applyEnrollmentResult(Device device, JsonNode payload) {
        if (payload == null) {
            return;
        }
        String deviceUserId = text(payload, "deviceUserId");
        if (deviceUserId == null) {
            return;
        }
        MemberDeviceMapping mapping = mappingRepository
                .findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null);
        if (mapping == null) {
            return;
        }
        String status = textOr(payload, "status", "FAILED");
        if ("ENROLLED".equalsIgnoreCase(status)) {
            mapping.setEnrollmentStatus(EnrollmentStatus.ENROLLED);
            mapping.setEnrolledAt(Instant.now());
        } else if ("GUIDED_PENDING".equalsIgnoreCase(status)) {
            mapping.setEnrollmentStatus(EnrollmentStatus.GUIDED_PENDING);
        } else {
            mapping.setEnrollmentStatus(EnrollmentStatus.FAILED);
        }
        mappingRepository.save(mapping);
    }

    private Optional<Device> resolveDevice(GatewayMessage message) {
        if (message.deviceId() == null) {
            return Optional.empty();
        }
        Gateway gateway = gatewayRepository.findByPublicId(message.gatewayId()).orElse(null);
        Device device = deviceRepository.findByPublicId(message.deviceId()).orElse(null);
        if (gateway == null || device == null || !device.getTenantId().equals(gateway.getTenantId())) {
            log.warn("Ignoring message for device {} not owned by gateway {}",
                    message.deviceId(), message.gatewayId());
            return Optional.empty();
        }
        return Optional.of(device);
    }

    private Optional<String> ack(GatewayMessage message) {
        return Optional.of(reply(GatewayMessageType.ACK, message.correlationId(),
                Map.of("messageId", message.messageId() == null ? "" : message.messageId())));
    }

    private String reply(GatewayMessageType type, String correlationId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", java.util.UUID.randomUUID().toString());
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("type", type.name());
        envelope.put("correlationId", correlationId);
        envelope.put("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null ? fallback : v;
    }

    private boolean boolAt(JsonNode node, String field) {
        return node != null && node.has(field) && node.get(field).asBoolean();
    }

    private Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Instant instantOr(JsonNode node, String field, Instant fallback) {
        Instant v = instant(node, field);
        return v == null ? fallback : v;
    }

    private DeviceConnectionState connectionState(JsonNode payload) {
        String v = text(payload, "connectionState");
        if (v == null) {
            return null;
        }
        try {
            return DeviceConnectionState.valueOf(v.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return DeviceConnectionState.UNKNOWN;
        }
    }
}
