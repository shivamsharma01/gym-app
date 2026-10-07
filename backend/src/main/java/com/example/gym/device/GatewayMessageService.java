package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.EnrollmentStatus;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SecurityEvent;
import com.example.gym.device.protocol.GatewayMessage;
import com.example.gym.device.protocol.GatewayMessageType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
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
import org.springframework.util.StringUtils;
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
    private final DeviceSyncCommandRepository commandRepository;
    private final DeviceService deviceService;
    private final AttendanceIngestionService attendanceIngestionService;
    private final DeviceSyncService deviceSyncService;
    private final DeviceReconciliationService reconciliationService;
    private final GatewayMessageDedupeService dedupeService;
    private final SecurityEventRepository securityEventRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final DeviceUserChangeService deviceUserChangeService;

    public GatewayMessageService(GatewayService gatewayService,
                                 GatewayRepository gatewayRepository,
                                 DeviceRepository deviceRepository,
                                 DeviceSyncCommandRepository commandRepository,
                                 DeviceService deviceService,
                                 AttendanceIngestionService attendanceIngestionService,
                                 DeviceSyncService deviceSyncService,
                                 DeviceReconciliationService reconciliationService,
                                 GatewayMessageDedupeService dedupeService,
                                 SecurityEventRepository securityEventRepository,
                                 MemberDeviceMappingRepository mappingRepository,
                                 JsonMapper jsonMapper,
                                 ApplicationEventPublisher events,
                                 DeviceUserChangeService deviceUserChangeService) {
        this.deviceUserChangeService = deviceUserChangeService;
        this.gatewayService = gatewayService;
        this.gatewayRepository = gatewayRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
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

    /**
     * Handles one gateway message as {@code boundGatewayId}, which the caller took from the
     * operational credential. A gateway id in the JSON body is discarded. A blank bound id is rejected.
     */
    public Optional<String> process(String raw, String boundGatewayId) {
        if (!StringUtils.hasText(boundGatewayId)) {
            return Optional.of(credentialRequiredError());
        }
        GatewayMessage message;
        try {
            message = jsonMapper.readValue(raw, GatewayMessage.class);
        } catch (RuntimeException ex) {
            log.warn("Rejecting malformed gateway message: {}", ex.getMessage());
            return Optional.of(reply(GatewayMessageType.ERROR, null, Map.of("error", "malformed message")));
        }
        message = new GatewayMessage(
                message.messageId(),
                message.timestamp(),
                boundGatewayId.trim(),
                message.deviceId(),
                message.type(),
                message.correlationId(),
                message.payload());
        if (dedupeService.alreadyProcessed(message.messageId())) {
            log.debug("Ignoring duplicate gateway messageId {}", message.messageId());
            return ack(message);
        }
        Optional<String> reply;
        try {
            reply = handle(message);
        } catch (ForeignDeviceRejected ex) {
            log.warn("Rejecting gateway message for a device gateway {} does not own", boundGatewayId);
            return Optional.of(reply(GatewayMessageType.ERROR, ex.correlationId,
                    Map.of("error", "device is not owned by this gateway")));
        } catch (RuntimeException ex) {
            // Not recorded as processed: the gateway keeps it (no ACK) and resends it on reconnect.
            log.error("Error handling gateway message {} ({})", message.type(), message.messageId(), ex);
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "processing failed")));
        }
        try {
            dedupeService.markProcessed(message.messageId(), message.gatewayId());
        } catch (RuntimeException ex) {
            // Processing is idempotent; a replay would only be processed again.
            log.warn("Could not record gateway messageId {} as processed: {}", message.messageId(), ex.getMessage());
        }
        return reply;
    }

    private Optional<String> handle(GatewayMessage message) {
        GatewayMessageType type;
        try {
            type = GatewayMessageType.valueOf(message.type());
        } catch (IllegalArgumentException ex) {
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "unknown type: " + message.type())));
        }
        if (type != GatewayMessageType.HEARTBEAT && type != GatewayMessageType.DEVICE_STATUS
                && FlowLog.isDebugEnabled("gateway")) {
            FlowLog.debug("gateway", "received {} device={} corr={} msg={}{}", type, message.deviceId(),
                    message.correlationId(), message.messageId(), describe(type, message.payload()));
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
                Device device = ownedDevice(message);
                if (device != null) {
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
                }
                yield ack(message);
            }
            case DEVICE_EVENT -> {
                Device device = ownedDevice(message);
                if (device != null) {
                    ingestEvent(device, message.payload(), message.timestamp());
                }
                yield ack(message);
            }
            case DEVICE_ALARM -> {
                Device device = ownedDevice(message);
                if (device != null) {
                    securityEventRepository.save(new SecurityEvent(
                            device.getTenantId(), device.getId(),
                            textOr(message.payload(), "type", "DEVICE_ALARM"),
                            instantOr(message.payload(), "occurredAt", message.timestamp()),
                            text(message.payload(), "details")));
                    events.publishEvent(new StaffLiveBroadcast(device.getTenantId(), "SECURITY_ALARM",
                            Map.of(
                                    "deviceId", device.getPublicId(),
                                    "type", textOr(message.payload(), "type", "DEVICE_ALARM"))));
                }
                yield ack(message);
            }
            case SYNC_RESULT -> {
                if (!syncResultAllowed(message)) {
                    throw new ForeignDeviceRejected(message.correlationId());
                }
                boolean skipped = boolAt(message.payload(), "skipped");
                deviceSyncService.handleResult(message.correlationId(), boolAt(message.payload(), "ok"),
                        skipped ? text(message.payload(), "reason") : text(message.payload(), "error"), skipped);
                yield ack(message);
            }
            case RECONCILIATION_RESULT -> {
                if (!syncResultAllowed(message)) {
                    throw new ForeignDeviceRejected(message.correlationId());
                }
                Device device = ownedDevice(message);
                if (device != null) {
                    boolean ok = message.payload() == null || !message.payload().has("ok")
                            || message.payload().get("ok").asBoolean();
                    FlowLog.info("gateway", "reconcile result device={} ok={} users={} events={} error={}",
                            device.getPublicId(), ok, arraySize(message.payload(), "deviceUsers"),
                            arraySize(message.payload(), "events"), text(message.payload(), "error"));
                    if (!ok) {
                        attendanceIngestionService.markReconciliationRequired(
                                device.getTenantId(), device.getId(), true);
                    } else {
                        // Users first, so punches by users this snapshot maps are credited on insert.
                        reconciliationService.applyDeviceUserSnapshot(device, message.payload());
                        JsonNode eventNodes = message.payload() == null ? null
                                : message.payload().get("events");
                        if (eventNodes != null && eventNodes.isArray()) {
                            eventNodes.forEach(e -> ingestEvent(device, e, message.timestamp()));
                        }
                        attendanceIngestionService.markReconciliationRequired(
                                device.getTenantId(), device.getId(), false);
                    }
                    // Complete the outbox command even when the gateway also sent SYNC_RESULT
                    if (message.correlationId() != null) {
                        deviceSyncService.handleResult(message.correlationId(), ok,
                                text(message.payload(), "error"));
                    }
                }
                yield ack(message);
            }
            case DEVICE_USER_CHANGED -> {
                Device device = ownedDevice(message);
                if (device != null) {
                    deviceUserChangeService.apply(device, message.payload());
                }
                yield ack(message);
            }
            case ENROLLMENT_RESULT -> {
                Device device = ownedDevice(message);
                if (device != null) {
                    applyEnrollmentResult(device, message.payload());
                }
                yield ack(message);
            }
            default -> Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    Map.of("error", "unsupported inbound type: " + type)));
        };
    }

    String credentialRequiredError() {
        return reply(GatewayMessageType.ERROR, null, Map.of("error", "gateway credential required"));
    }

    private static int arraySize(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node != null && node.isArray() ? node.size() : 0;
    }

    /** Short, safe summary of the fields that explain a message (no photos or credentials). */
    private String describe(GatewayMessageType type, JsonNode payload) {
        if (payload == null) {
            return "";
        }
        return switch (type) {
            case SYNC_RESULT -> " ok=" + text(payload, "ok") + " skipped=" + text(payload, "skipped")
                    + " error=" + text(payload, "error") + " reason=" + text(payload, "reason");
            case DEVICE_EVENT -> " user=" + text(payload, "deviceUserId") + " granted=" + text(payload, "granted")
                    + " recNo=" + text(payload, "recNo") + " at=" + text(payload, "occurredAt");
            case DEVICE_USER_CHANGED -> " user=" + text(payload, "deviceUserId") + " isNew=" + text(payload, "isNew")
                    + " deleted=" + text(payload, "deleted") + " nameChanged=" + text(payload, "nameChanged")
                    + " accessChanged=" + text(payload, "frozenChanged") + "/" + text(payload, "validityChanged")
                    + " faceChanged=" + text(payload, "faceChanged") + " changedAt=" + text(payload, "deviceChangedAt");
            case RECONCILIATION_RESULT -> " users=" + arraySize(payload, "deviceUsers")
                    + " events=" + arraySize(payload, "events");
            default -> "";
        };
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

    /**
     * Device named on the message, when it belongs to the authenticated gateway. An unknown device
     * id is ignored (the message is still acknowledged). A device owned by another gateway is rejected.
     */
    private Device ownedDevice(GatewayMessage message) {
        if (!StringUtils.hasText(message.deviceId())) {
            return null;
        }
        Gateway gateway = gatewayRepository.findByPublicId(message.gatewayId()).orElse(null);
        if (gateway == null) {
            throw new ForeignDeviceRejected(message.correlationId());
        }
        Device device = deviceRepository.findByPublicId(message.deviceId()).orElse(null);
        if (device == null) {
            log.warn("Ignoring message for unknown device {}", message.deviceId());
            return null;
        }
        if (!gateway.getTenantId().equals(device.getTenantId())
                || device.getGatewayId() == null
                || !gateway.getId().equals(device.getGatewayId())) {
            log.warn("Rejecting message for device {} not owned by gateway {}",
                    device.getPublicId(), gateway.getPublicId());
            throw new ForeignDeviceRejected(message.correlationId());
        }
        return device;
    }

    /**
     * A sync result may complete only a command for a device this gateway owns. An unknown
     * correlation id stays a no-op, matching the existing result handler.
     */
    private boolean syncResultAllowed(GatewayMessage message) {
        if (!StringUtils.hasText(message.correlationId())) {
            return true;
        }
        DeviceSyncCommand command = commandRepository.findByCorrelationId(message.correlationId()).orElse(null);
        if (command == null) {
            return true;
        }
        Gateway gateway = gatewayRepository.findByPublicId(message.gatewayId()).orElse(null);
        if (gateway == null) {
            return false;
        }
        Device device = deviceRepository.findById(command.getDeviceId()).orElse(null);
        return device != null
                && gateway.getTenantId().equals(device.getTenantId())
                && gateway.getId().equals(device.getGatewayId());
    }

    /** Raised when a gateway addresses a device it does not own. Not recorded as processed. */
    private static final class ForeignDeviceRejected extends RuntimeException {
        private final String correlationId;

        private ForeignDeviceRejected(String correlationId) {
            this.correlationId = correlationId;
        }
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
