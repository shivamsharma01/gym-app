package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.tenant.TenantGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox for staff commands to a reader (§9): door open/close and scan now. A command
 * is enqueued in the request's transaction; {@link #dispatchDue()} delivers it to the gateway with
 * exponential backoff + jitter and dead-lettering; the gateway's {@code SYNC_RESULT} drives {@link #handleResult}. We
 * never fabricate a device outcome — a command only SUCCEEDS on an explicit gateway result.
 */
@Service
public class DeviceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncService.class);

    private final DeviceSyncCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final GatewayRepository gatewayRepository;
    private final GatewaySessionRegistry sessionRegistry;
    private final GatewayCommandTransport transport;
    private final GatewayProperties properties;
    private final AuditService auditService;
    private final JsonMapper jsonMapper;
    private final LogThrottle unansweredWarnings = new LogThrottle(Duration.ofMinutes(5));
    private final LogThrottle noGatewayWarnings = new LogThrottle(Duration.ofMinutes(10));

    public DeviceSyncService(DeviceSyncCommandRepository commandRepository,
                             DeviceRepository deviceRepository,
                             GatewayRepository gatewayRepository,
                             GatewaySessionRegistry sessionRegistry,
                             GatewayCommandTransport transport,
                             GatewayProperties properties,
                             AuditService auditService,
                             JsonMapper jsonMapper) {
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.gatewayRepository = gatewayRepository;
        this.sessionRegistry = sessionRegistry;
        this.transport = transport;
        this.properties = properties;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
    }

    /** The oldest command of this type for the device that has not finished yet. */
    @Transactional(readOnly = true)
    public Optional<DeviceSyncCommand> openCommand(Long deviceId, SyncCommandType type) {
        return commandRepository.findFirstByDeviceIdAndTypeAndStateInOrderByCreatedAtAsc(deviceId, type, OPEN_STATES);
    }

    /**
     * Enqueues a command inside the request's transaction, so the request and its command commit
     * together.
     */
    @Transactional
    public DeviceSyncCommand enqueue(Long tenantId, Long deviceId, SyncCommandType type, Map<String, Object> payload) {
        String correlationId = UUID.randomUUID().toString();
        String payloadJson = payload == null ? null : jsonMapper.writeValueAsString(payload);
        DeviceSyncCommand command = new DeviceSyncCommand(tenantId, deviceId, type, payloadJson, correlationId,
                properties.getOutbox().getMaxAttempts(), Instant.now());
        DeviceSyncCommand saved = commandRepository.save(command);
        FlowLog.debug("sync", "enqueued {} corr={} device={}", type, correlationId, deviceId);
        warnIfNoGateway(deviceId);
        auditService.record(AuditActions.DEVICE_SYNC_ENQUEUED, AuditActions.RESULT_SUCCESS,
                "DeviceSyncCommand", saved.getPublicId(), Map.of("type", type.name()));
        return saved;
    }

    /**
     * Reclaims DISPATCHED / ACKNOWLEDGED commands whose gateway is no longer connected. A connected
     * gateway works through its own per-device queue and answers with SYNC_RESULT when the reader
     * finishes, which can be long after {@code dispatch-timeout}. Reclaiming those in-flight
     * commands would send the same command twice.
     */
    @Transactional
    public int reclaimStaleDispatched() {
        Duration timeout = properties.getOutbox().getDispatchTimeout();
        Instant cutoff = Instant.now().minus(timeout);
        List<DeviceSyncCommand> stale = commandRepository.findByStateInAndDispatchedAtLessThanEqual(
                List.of(SyncCommandState.DISPATCHED, SyncCommandState.ACKNOWLEDGED),
                cutoff,
                PageRequest.of(0, properties.getOutbox().getBatchSize()));
        int reclaimed = 0;
        int unanswered = 0;
        for (DeviceSyncCommand command : stale) {
            if (gatewayConnected(command)) {
                unanswered++;
                continue;
            }
            command.setState(SyncCommandState.RETRYING);
            command.setNextAttemptAt(Instant.now());
            command.setLastError(truncate("Reclaimed: gateway disconnected before SYNC_RESULT", 500));
            commandRepository.save(command);
            log.info("Reclaimed stale command {} (was {})", command.getCorrelationId(),
                    command.getDispatchedAt());
            reclaimed++;
        }
        if (unanswered > 0 && unansweredWarnings.allow("unanswered")) {
            DeviceSyncCommand oldest = stale.stream().filter(this::gatewayConnected).findFirst().orElse(stale.get(0));
            FlowLog.warn("sync", "{} command(s) were sent more than {} ago to a connected gateway and have no result yet "
                            + "(for example {} corr={} device={} sent at {}). Check the gateway log for that correlation id.",
                    unanswered, timeout, oldest.getType(), oldest.getCorrelationId(), oldest.getDeviceId(),
                    oldest.getDispatchedAt());
        }
        return reclaimed;
    }

    private boolean gatewayConnected(DeviceSyncCommand command) {
        if (command.getDeviceId() == null) {
            return false;
        }
        Device device = deviceRepository.findById(command.getDeviceId()).orElse(null);
        if (device == null || device.getGatewayId() == null) {
            return false;
        }
        Gateway gateway = gatewayRepository.findById(device.getGatewayId()).orElse(null);
        return gateway != null && sessionRegistry.isOnline(gateway.getPublicId());
    }

    /** Claims due commands and attempts delivery to their gateways. Returns the number dispatched. */
    @Transactional
    public int dispatchDue() {
        reclaimStaleDispatched();
        // Only devices behind a connected gateway. Commands for an offline gateway wait untouched: they are made due
        // when it connects (wakeGateway), and a gateway on the REST fallback claims its own.
        List<Long> liveDevices = connectedDeviceIds();
        if (liveDevices.isEmpty()) {
            return 0;
        }
        List<DeviceSyncCommand> due = commandRepository
                .claimDue(
                        liveDevices,
                        List.of(SyncCommandState.PENDING, SyncCommandState.RETRYING),
                        Instant.now(),
                        PageRequest.of(0, properties.getOutbox().getBatchSize()));
        return deliver(due);
    }

    /** Commands for a device without a gateway are never dispatched; they wait until one is assigned. */
    private void warnIfNoGateway(Long deviceId) {
        if (deviceId == null || !noGatewayWarnings.allow(deviceId.toString())) {
            return;
        }
        deviceRepository.findById(deviceId)
                .filter(d -> d.getGatewayId() == null)
                .ifPresent(d -> FlowLog.warn("sync", "device {} has no gateway assigned: its commands wait until one is assigned",
                        d.getPublicId()));
    }

    private List<Long> connectedDeviceIds() {
        List<Long> ids = new ArrayList<>();
        for (String publicId : sessionRegistry.connectedGateways()) {
            gatewayRepository.findByPublicId(publicId).ifPresent(gateway ->
                    deviceRepository.findByGatewayId(gateway.getId()).forEach(d -> ids.add(d.getId())));
        }
        return ids;
    }

    private int deliver(List<DeviceSyncCommand> due) {
        int dispatched = 0;
        int waiting = 0;
        int failed = 0;
        for (DeviceSyncCommand command : due) {
            GatewayCommandTransport.Outcome outcome;
            try {
                outcome = transport.dispatch(command);
            } catch (RuntimeException ex) {
                log.warn("Transport error dispatching {}: {}", command.getCorrelationId(), ex.getMessage());
                outcome = GatewayCommandTransport.Outcome.FAILED;
            }
            switch (outcome) {
                case SENT -> {
                    command.setState(SyncCommandState.DISPATCHED);
                    command.setDispatchedAt(Instant.now());
                    command.setAttemptCount(command.getAttemptCount() + 1);
                    dispatched++;
                    FlowLog.debug("sync", "dispatched {} corr={} device={} attempt={}",
                            command.getType(), command.getCorrelationId(), command.getDeviceId(),
                            command.getAttemptCount());
                }
                case NOT_CONNECTED -> {
                    waitForGateway(command);
                    waiting++;
                }
                case FAILED -> {
                    failAttempt(command, "Delivery to the gateway failed");
                    failed++;
                }
            }
            commandRepository.save(command);
        }
        if (!due.isEmpty()) {
            FlowLog.info("sync", "dispatch batch: due={} sent={} waitingForGateway={} deliveryFailed={}",
                    due.size(), dispatched, waiting, failed);
        }
        return dispatched;
    }

    /** Applies a gateway result. Idempotent: replays for already-terminal commands are ignored. */
    @Transactional
    public void handleResult(String correlationId, boolean ok, String error) {
        DeviceSyncCommand command = commandRepository.findByCorrelationId(correlationId).orElse(null);
        if (command == null) {
            log.debug("SYNC_RESULT for unknown correlationId {}", correlationId);
            return;
        }
        if (command.getState().isTerminal()) {
            FlowLog.debug("sync", "late result for {} corr={} ignored: already {}",
                    command.getType(), correlationId, command.getState());
            return;
        }
        command.setAcknowledgedAt(Instant.now());
        if (ok) {
            FlowLog.debug("sync", "{} succeeded corr={} device={} attempt={} took={}ms",
                    command.getType(), correlationId, command.getDeviceId(), command.getAttemptCount(),
                    sinceDispatch(command));
            command.setState(SyncCommandState.SUCCEEDED);
            command.setCompletedAt(Instant.now());
            command.setLastError(null);
        } else {
            failAttempt(command, error);
        }
        commandRepository.save(command);
    }

    @Transactional
    public DeviceSyncCommand retry(String publicId, Long tenantId) {
        DeviceSyncCommand command = getByPublicId(publicId, tenantId);
        if (command.getState() == SyncCommandState.SUCCEEDED) {
            throw CommonExceptions.badRequest("Command already succeeded");
        }
        command.setState(SyncCommandState.PENDING);
        command.setAttemptCount(0);
        command.setNextAttemptAt(Instant.now());
        command.setLastError(null);
        command.setCompletedAt(null);
        DeviceSyncCommand saved = commandRepository.save(command);
        FlowLog.info("sync", "manual retry of {} corr={} device={}",
                saved.getType(), saved.getCorrelationId(), saved.getDeviceId());
        auditService.record(AuditActions.DEVICE_SYNC_RETRIED, AuditActions.RESULT_SUCCESS,
                "DeviceSyncCommand", saved.getPublicId(), null);
        return saved;
    }

    @Transactional
    public DeviceSyncCommand cancel(String publicId, Long tenantId) {
        DeviceSyncCommand command = getByPublicId(publicId, tenantId);
        if (command.getState().isTerminal()) {
            throw CommonExceptions.badRequest("Command is already in a terminal state");
        }
        command.setState(SyncCommandState.CANCELLED);
        command.setCompletedAt(Instant.now());
        DeviceSyncCommand saved = commandRepository.save(command);
        FlowLog.info("sync", "manual cancel of {} corr={} device={}",
                saved.getType(), saved.getCorrelationId(), saved.getDeviceId());
        auditService.record(AuditActions.DEVICE_SYNC_CANCELLED, AuditActions.RESULT_SUCCESS,
                "DeviceSyncCommand", saved.getPublicId(), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<DeviceSyncCommand> list(
            Long tenantId, Long deviceId, boolean openOnly,
            org.springframework.data.domain.Pageable pageable) {
        if (openOnly) {
            java.util.List<SyncCommandState> open = java.util.List.of(
                    SyncCommandState.PENDING, SyncCommandState.DISPATCHED,
                    SyncCommandState.ACKNOWLEDGED, SyncCommandState.RETRYING);
            return deviceId == null
                    ? commandRepository.findByTenantIdAndStateInOrderByCreatedAtDesc(tenantId, open, pageable)
                    : commandRepository.findByTenantIdAndDeviceIdAndStateInOrderByCreatedAtDesc(
                            tenantId, deviceId, open, pageable);
        }
        return deviceId == null
                ? commandRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                : commandRepository.findByTenantIdAndDeviceIdOrderByCreatedAtDesc(tenantId, deviceId, pageable);
    }

    @Transactional(readOnly = true)
    public DeviceSyncCommand getByPublicId(String publicId, Long tenantId) {
        DeviceSyncCommand command = commandRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Sync command"));
        TenantGuard.check(command.getTenantId(), tenantId, "Sync command");
        return command;
    }


    static final List<SyncCommandState> OPEN_STATES = List.of(
            SyncCommandState.PENDING, SyncCommandState.DISPATCHED,
            SyncCommandState.ACKNOWLEDGED, SyncCommandState.RETRYING);

    /**
     * The gateway is offline: the command keeps its attempts and waits. It is checked again after
     * {@code offlineRecheck} and released at once when the gateway reconnects ({@link #wakeGateway}).
     */
    private void waitForGateway(DeviceSyncCommand command) {
        command.setState(SyncCommandState.RETRYING);
        command.setLastError("Waiting for the gateway to connect");
        command.setNextAttemptAt(Instant.now().plus(properties.getOutbox().getOfflineRecheck()));
        FlowLog.debug("sync", "{} corr={} device={} waits: gateway offline, recheck at {}",
                command.getType(), command.getCorrelationId(), command.getDeviceId(), command.getNextAttemptAt());
    }

    /** Makes every waiting command of the gateway's devices due now (called when it connects). */
    @Transactional
    public int wakeGateway(List<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return 0;
        }
        int released = commandRepository.makeDueNow(deviceIds,
                List.of(SyncCommandState.PENDING, SyncCommandState.RETRYING), Instant.now());
        FlowLog.info("sync", "gateway connected: {} waiting command(s) made due for devices {}", released, deviceIds);
        return released;
    }

    private void failAttempt(DeviceSyncCommand command, String error) {
        command.setAttemptCount(command.getAttemptCount() + 1);
        command.setLastError(truncate(error, 500));
        if (command.getAttemptCount() >= command.getMaxAttempts()) {
            command.setState(SyncCommandState.DEAD_LETTER);
            command.setCompletedAt(Instant.now());
            FlowLog.warn("device", "command dead-lettered correlation={} type={} attempts={} error={}",
                    command.getCorrelationId(), command.getType(), command.getAttemptCount(), error);
            log.warn("Command {} dead-lettered after {} attempts: {}",
                    command.getCorrelationId(), command.getAttemptCount(), error);
        } else {
            command.setState(SyncCommandState.RETRYING);
            command.setNextAttemptAt(Instant.now().plus(backoff(command.getAttemptCount())));
            FlowLog.info("sync", "{} failed corr={} device={} attempt {}/{}, retry at {}: {}",
                    command.getType(), command.getCorrelationId(), command.getDeviceId(),
                    command.getAttemptCount(), command.getMaxAttempts(), command.getNextAttemptAt(), error);
        }
    }

    private Long sinceDispatch(DeviceSyncCommand command) {
        return command.getDispatchedAt() == null ? null
                : Duration.between(command.getDispatchedAt(), Instant.now()).toMillis();
    }

    private Duration backoff(int attempt) {
        var outbox = properties.getOutbox();
        long baseMillis = outbox.getBaseBackoff().toMillis();
        long capMillis = outbox.getMaxBackoff().toMillis();
        long exp = baseMillis * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(exp, capMillis);
        long jitterMillis = outbox.getJitter().toMillis();
        long jitter = jitterMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMillis + 1);
        return Duration.ofMillis(capped + jitter);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
