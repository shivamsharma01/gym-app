package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.SecurityEvent;
import com.example.gym.device.protocol.GatewayMessage;
import com.example.gym.device.protocol.GatewayMessageType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.SecurityEventRepository;
import com.example.gym.live.StaffLiveBroadcast;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * link stays healthy. Every ERROR names the gateway's message id; a terminal refusal is recorded so
 * the fence can drop it, and a retryable failure is left unrecorded for reconnect.
 */
@Service
public class GatewayMessageService {

    private static final Logger log = LoggerFactory.getLogger(GatewayMessageService.class);
    private static final int READER_UPDATE_LOG_EVERY = 100;

    private final Map<Long, AtomicInteger> savedPeople = new ConcurrentHashMap<>();
    private final GatewayService gatewayService;
    private final GatewayRepository gatewayRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceSyncCommandRepository commandRepository;
    private final DeviceService deviceService;
    private final DeviceSyncService deviceSyncService;
    private final GatewayMessageDedupeService dedupeService;
    private final SecurityEventRepository securityEventRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final DeviceUserChangeService deviceUserChangeService;

    public GatewayMessageService(GatewayService gatewayService,
                                 GatewayRepository gatewayRepository,
                                 DeviceRepository deviceRepository,
                                 DeviceSyncCommandRepository commandRepository,
                                 DeviceService deviceService,
                                 DeviceSyncService deviceSyncService,
                                 GatewayMessageDedupeService dedupeService,
                                 SecurityEventRepository securityEventRepository,
                                 JsonMapper jsonMapper,
                                 ApplicationEventPublisher events,
                                 DeviceUserChangeService deviceUserChangeService) {
        this.deviceUserChangeService = deviceUserChangeService;
        this.gatewayService = gatewayService;
        this.gatewayRepository = gatewayRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.deviceService = deviceService;
        this.deviceSyncService = deviceSyncService;
        this.dedupeService = dedupeService;
        this.securityEventRepository = securityEventRepository;
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
            // List follow-up (seed/claim) is idempotent; replay when the first ACK skipped it.
            ensureListFollowUpIfComplete(message);
            return ack(message);
        }
        long started = System.nanoTime();
        boolean completeList = isCompleteReaderList(message);
        if (completeList) {
            FlowLog.info("device", "list received device={} msg={} people={}",
                    message.deviceId(), message.messageId(), listPeople(message.payload()));
        }
        Optional<String> reply;
        try {
            reply = handle(message);
        } catch (ForeignDeviceRejected ex) {
            log.warn("Rejecting gateway message for a device gateway {} does not own", boundGatewayId);
            recordProcessed(message);
            return Optional.of(reply(GatewayMessageType.ERROR, ex.correlationId,
                    errorPayload(message, "device is not owned by this gateway", false)));
        } catch (ObservationOrder.Unordered ex) {
            log.warn("Refusing gateway message {} ({}) for device {}: {}", message.type(), message.messageId(),
                    message.deviceId(), ex.getMessage());
            recordProcessed(message);
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    errorPayload(message, "observation out of order", false)));
        } catch (RuntimeException ex) {
            // Not recorded: the gateway keeps it and resends on reconnect. Named so the fence can
            // drop only this id once the server later saves it, without pausing the whole reader.
            if (completeList) {
                FlowLog.info("device", "list failed device={} msg={} ms={}{}",
                        message.deviceId(), message.messageId(),
                        (System.nanoTime() - started) / 1_000_000L, failureContext(message));
            }
            log.error("Error handling gateway message {} ({}) device={}{}",
                    message.type(), message.messageId(), message.deviceId(), failureContext(message), ex);
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    errorPayload(message, "processing failed", true)));
        }
        long markStarted = System.nanoTime();
        recordProcessed(message);
        if (completeList) {
            FlowLog.info("device", "list markProcessed device={} msg={} handleMs={} markMs={}",
                    message.deviceId(), message.messageId(),
                    (markStarted - started) / 1_000_000L,
                    (System.nanoTime() - markStarted) / 1_000_000L);
        }
        try {
            deviceUserChangeService.runFollowUp();
        } catch (RuntimeException ex) {
            log.warn("Follow-up after saving gateway messageId {} failed: {}", message.messageId(), ex.getMessage());
        }
        return reply;
    }

    private void ensureListFollowUpIfComplete(GatewayMessage message) {
        if (!isCompleteReaderList(message)) {
            return;
        }
        try {
            Device device = ownedDevice(message);
            if (device == null) {
                return;
            }
            deviceUserChangeService.ensureListFollowUp(device);
        } catch (RuntimeException ex) {
            log.warn("Follow-up on replay of gateway messageId {} failed: {}", message.messageId(), ex.getMessage());
        }
    }

    private static boolean isCompleteReaderList(GatewayMessage message) {
        if (!GatewayMessageType.DEVICE_USER_CHANGED.name().equals(message.type())) {
            return false;
        }
        JsonNode payload = message.payload();
        if (payload == null) {
            return false;
        }
        JsonNode users = payload.get("users");
        return users != null && users.isArray()
                && payload.path("announcedTotal").asInt(-1) == users.size();
    }

    private static int listPeople(JsonNode payload) {
        JsonNode users = payload == null ? null : payload.get("users");
        return users != null && users.isArray() ? users.size() : 0;
    }

    private void recordProcessed(GatewayMessage message) {
        try {
            dedupeService.markProcessed(message.messageId(), message.gatewayId());
        } catch (RuntimeException ex) {
            // Processing is idempotent; a replay would only be processed again.
            log.warn("Could not record gateway messageId {} as processed: {}", message.messageId(), ex.getMessage());
        }
    }

    private Optional<String> handle(GatewayMessage message) {
        GatewayMessageType type;
        try {
            type = GatewayMessageType.valueOf(message.type());
        } catch (IllegalArgumentException ex) {
            return Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    errorPayload(message, "unknown type: " + message.type(), false)));
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
                deviceSyncService.handleResult(message.correlationId(), boolAt(message.payload(), "ok"),
                        text(message.payload(), "error"));
                yield ack(message);
            }
            case DEVICE_USER_CHANGED -> {
                Device device = ownedDevice(message);
                if (device != null) {
                    deviceUserChangeService.apply(device, message.payload());
                    noteReaderUpdate(device, message.payload());
                }
                yield ack(message);
            }
            default -> Optional.of(reply(GatewayMessageType.ERROR, message.correlationId(),
                    errorPayload(message, "unsupported inbound type: " + type, false)));
        };
    }

    /**
     * A full list resets the count. People sent one by one log every hundred, because that upload
     * pauses member sync and otherwise looks idle.
     */
    private void noteReaderUpdate(Device device, JsonNode payload) {
        if (payload != null && payload.has("users") && payload.get("users").isArray()) {
            savedPeople.put(device.getId(), new AtomicInteger());
            log.info("{}: saved a reader list of {} people. Member sync stays paused until the gateway confirms this list is complete.",
                    device.getName(), payload.get("users").size());
            return;
        }
        int count = savedPeople.computeIfAbsent(device.getId(), id -> new AtomicInteger()).incrementAndGet();
        if (count == 1 || count % READER_UPDATE_LOG_EVERY == 0) {
            log.info("{}: saved {} people from the reader. Member sync stays paused until the whole list is saved.",
                    device.getName(), count);
        }
    }

    String credentialRequiredError() {
        return reply(GatewayMessageType.ERROR, null, Map.of("error", "gateway credential required"));
    }

    /** Device user id, or the size of a full list. No name and no photo. */
    private String failureContext(GatewayMessage message) {
        JsonNode payload = message.payload();
        if (payload == null) {
            return "";
        }
        String user = text(payload, "deviceUserId");
        if (user != null && !user.isBlank()) {
            return " user=" + user;
        }
        JsonNode users = payload.get("users");
        if (users != null && users.isArray()) {
            return " people=" + users.size();
        }
        return "";
    }

    /** Short, safe summary of the fields that explain a message (no photos or credentials). */
    private String describe(GatewayMessageType type, JsonNode payload) {
        if (payload == null) {
            return "";
        }
        return switch (type) {
            case SYNC_RESULT -> " ok=" + text(payload, "ok") + " error=" + text(payload, "error");
            case DEVICE_USER_CHANGED -> " user=" + text(payload, "deviceUserId") + " isNew=" + text(payload, "isNew")
                    + " deleted=" + text(payload, "deleted") + " nameChanged=" + text(payload, "nameChanged")
                    + " accessChanged=" + text(payload, "frozenChanged") + "/" + text(payload, "validityChanged")
                    + " faceChanged=" + text(payload, "faceChanged") + " changedAt=" + text(payload, "deviceChangedAt");
            default -> "";
        };
    }

    /**
     * Device named on the message, when it belongs to the authenticated gateway. An unknown device
     * id is ignored (the message is still acknowledged). A device owned by another gym's gateway is rejected.
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

    /** Raised when a gateway addresses a device it does not own. Recorded so the fence can close. */
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

    /**
     * Every ERROR names the gateway's message id. {@code retryable} false means the server recorded
     * it and the gateway must drop it; true means the gateway keeps it for reconnect.
     */
    private static Map<String, Object> errorPayload(GatewayMessage message, String error, boolean retryable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        payload.put("messageId", message.messageId() == null ? "" : message.messageId());
        payload.put("retryable", retryable);
        return payload;
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
