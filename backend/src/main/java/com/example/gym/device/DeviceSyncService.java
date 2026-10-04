package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.live.StaffLiveBroadcast;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.membership.DeviceSyncState;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.tenant.TenantGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional-outbox synchronization engine (§9). Business changes enqueue commands in the same
 * transaction; {@link #dispatchDue()} delivers them to the gateway with exponential backoff +
 * jitter and dead-lettering; the gateway's {@code SYNC_RESULT} drives {@link #handleResult}. We
 * never fabricate a device outcome — a command only SUCCEEDS on an explicit gateway result.
 */
@Service
public class DeviceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncService.class);

    private final DeviceSyncCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final GatewayRepository gatewayRepository;
    private final GatewaySessionRegistry sessionRegistry;
    private final MemberDeviceMappingRepository mappingRepository;
    private final MembershipRepository membershipRepository;
    private final GatewayCommandTransport transport;
    private final GatewayProperties properties;
    private final AuditService auditService;
    private final JsonMapper jsonMapper;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher events;
    private final LogThrottle unansweredWarnings = new LogThrottle(Duration.ofMinutes(5));
    private final LogThrottle noGatewayWarnings = new LogThrottle(Duration.ofMinutes(10));

    public DeviceSyncService(DeviceSyncCommandRepository commandRepository,
                             DeviceRepository deviceRepository,
                             GatewayRepository gatewayRepository,
                             GatewaySessionRegistry sessionRegistry,
                             MemberDeviceMappingRepository mappingRepository,
                             MembershipRepository membershipRepository,
                             GatewayCommandTransport transport,
                             GatewayProperties properties,
                             AuditService auditService,
                             JsonMapper jsonMapper,
                             MemberRepository memberRepository,
                             ApplicationEventPublisher events) {
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.gatewayRepository = gatewayRepository;
        this.sessionRegistry = sessionRegistry;
        this.mappingRepository = mappingRepository;
        this.membershipRepository = membershipRepository;
        this.transport = transport;
        this.properties = properties;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
        this.memberRepository = memberRepository;
        this.events = events;
    }

    /**
     * Cancels open commands of the given types for one member on one device (a newer command makes
     * them obsolete, e.g. an older face version that has not been delivered yet).
     */
    @Transactional
    public void supersede(Long deviceId, Long memberId, List<SyncCommandType> types) {
        List<DeviceSyncCommand> open = commandRepository.findByDeviceIdAndMemberIdAndTypeInAndStateIn(
                deviceId, memberId, types, OPEN_STATES);
        for (DeviceSyncCommand command : open) {
            command.setState(SyncCommandState.CANCELLED);
            command.setCompletedAt(Instant.now());
            command.setLastError("Superseded by a newer change");
            commandRepository.save(command);
            FlowLog.debug("sync", "superseded {} corr={} device={} member={} (a newer change replaces it)",
                    command.getType(), command.getCorrelationId(), deviceId, memberId);
        }
    }

    /**
     * Enqueues a command. Intended to run inside the business transaction that caused it (true
     * transactional outbox), so the change and its command commit atomically.
     */
    @Transactional
    public DeviceSyncCommand enqueue(Long tenantId, Long deviceId, Long memberId, Long membershipId,
                                     SyncCommandType type, Map<String, Object> payload) {
        if (memberId != null) {
            List<SyncCommandType> replaced = supersededBy(type);
            if (!replaced.isEmpty()) {
                supersede(deviceId, memberId, replaced);
            }
        }
        String correlationId = UUID.randomUUID().toString();
        payload = withChangeTimes(memberId, type, payload);
        String payloadJson = payload == null ? null : jsonMapper.writeValueAsString(payload);
        DeviceSyncCommand command = new DeviceSyncCommand(tenantId, deviceId, memberId, membershipId,
                type, payloadJson, correlationId, properties.getOutbox().getMaxAttempts(), Instant.now());
        DeviceSyncCommand saved = commandRepository.save(command);

        markState(saved, DeviceSyncState.PENDING);
        FlowLog.debug("sync", "enqueued {} corr={} device={} member={} user={}",
                type, correlationId, deviceId, memberId, payload == null ? null : payload.get("deviceUserId"));
        warnIfNoGateway(deviceId);
        auditService.record(AuditActions.DEVICE_SYNC_ENQUEUED, AuditActions.RESULT_SUCCESS,
                "DeviceSyncCommand", saved.getPublicId(), Map.of("type", type.name()));
        return saved;
    }

    /**
     * Reclaims DISPATCHED / ACKNOWLEDGED commands whose gateway is no longer connected. A connected
     * gateway works through its own per-device queue and answers with SYNC_RESULT when the reader
     * finishes, which can be long after {@code dispatch-timeout}. Reclaiming those in-flight
     * commands resends them and is what piled the same user up several times.
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

    /** Access commands replace each other. A removal replaces every earlier write for that person. */
    private static List<SyncCommandType> supersededBy(SyncCommandType type) {
        if (ACCESS_TYPES.contains(type)) {
            return ACCESS_TYPES;
        }
        if (type == SyncCommandType.REMOVE_USER) {
            return MEMBER_WRITES;
        }
        if (type == SyncCommandType.CREATE_USER || type == SyncCommandType.UPDATE_USER
                || type == SyncCommandType.REPORT_DEVICE_USER
                || type == SyncCommandType.UPSERT_FACE || type == SyncCommandType.DELETE_FACE
                || type == SyncCommandType.ENROLL_FACE) {
            return List.of(type);
        }
        return List.of();
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
                    FlowLog.debug("sync", "dispatched {} corr={} device={} member={} attempt={}",
                            command.getType(), command.getCorrelationId(), command.getDeviceId(),
                            command.getMemberId(), command.getAttemptCount());
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
        handleResult(correlationId, ok, error, false);
    }

    /**
     * {@code skipped}: the gateway did not apply the command because its devices already hold the
     * same or a newer change of those fields (a newer change reaches the server as
     * DEVICE_USER_CHANGED). The gateway converges the device to its latest state either way, so
     * the device counts as in sync for this command; the skip is recorded so staff can see it.
     */
    @Transactional
    public void handleResult(String correlationId, boolean ok, String error, boolean skipped) {
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
        if (ok && !skipped && FlowLog.isDebugEnabled("sync")) {
            FlowLog.debug("sync", "{} succeeded corr={} device={} member={} user={} attempt={} took={}ms",
                    command.getType(), correlationId, command.getDeviceId(), command.getMemberId(),
                    payloadText(command, "deviceUserId"), command.getAttemptCount(), sinceDispatch(command));
        }
        if (ok) {
            command.setState(SyncCommandState.SUCCEEDED);
            command.setCompletedAt(Instant.now());
            if (skipped) {
                command.setLastError(truncate("Skipped by gateway (device already up to date): " + error, 500));
                recordSkipped(command, error);
            } else {
                command.setLastError(null);
            }
            if (isFaceCommand(command)) {
                applyFaceSuccess(command);
            } else {
                markState(command, DeviceSyncState.SYNCED);
            }
        } else {
            failAttempt(command, error);
        }
        commandRepository.save(command);
        if (ok && command.getType() == SyncCommandType.CREATE_USER && command.getMemberId() != null) {
            String deviceUserId = payloadText(command, "deviceUserId");
            if (deviceUserId != null) {
                events.publishEvent(new DeviceUserCreated(command.getDeviceId(), command.getMemberId(), deviceUserId));
            }
        }
        publishMemberSync(command);
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
        FlowLog.info("sync", "manual retry of {} corr={} device={} member={}",
                saved.getType(), saved.getCorrelationId(), saved.getDeviceId(), saved.getMemberId());
        markState(saved, DeviceSyncState.PENDING);
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
        FlowLog.info("sync", "manual cancel of {} corr={} device={} member={}",
                saved.getType(), saved.getCorrelationId(), saved.getDeviceId(), saved.getMemberId());
        settleAfterCancel(saved);
        publishMemberSync(saved);
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

    @Transactional(readOnly = true)
    public boolean hasActiveReconcile(Long deviceId) {
        return commandRepository.existsByDeviceIdAndTypeAndStateIn(
                deviceId, SyncCommandType.RECONCILE_DEVICE, OPEN_STATES);
    }

    /** Cancels every open command for a member (e.g. the member was deleted on a device). */
    @Transactional
    public int cancelOpenForMember(Long memberId) {
        List<DeviceSyncCommand> open = commandRepository.findByMemberIdAndStateIn(memberId, OPEN_STATES);
        for (DeviceSyncCommand command : open) {
            command.setState(SyncCommandState.CANCELLED);
            command.setCompletedAt(Instant.now());
        }
        commandRepository.saveAll(open);
        if (!open.isEmpty()) {
            FlowLog.info("sync", "cancelled {} open command(s) for member={}", open.size(), memberId);
        }
        return open.size();
    }

    /** Open (not yet terminal) commands for a member, used by the member device-sync view. */
    @Transactional(readOnly = true)
    public List<DeviceSyncCommand> openCommandsForMember(Long memberId) {
        return commandRepository.findByMemberIdAndStateIn(memberId, OPEN_STATES);
    }

    // --- internals -------------------------------------------------------------------------------

    static final List<SyncCommandState> OPEN_STATES = List.of(
            SyncCommandState.PENDING, SyncCommandState.DISPATCHED,
            SyncCommandState.ACKNOWLEDGED, SyncCommandState.RETRYING);

    /** Commands that write a member's details / access on a device (the mapping's {@code syncState}). */
    private static final List<SyncCommandType> USER_TYPES = List.of(
            SyncCommandType.CREATE_USER, SyncCommandType.UPDATE_USER, SyncCommandType.DISABLE_USER,
            SyncCommandType.ENABLE_USER, SyncCommandType.UPDATE_VALIDITY, SyncCommandType.UPDATE_ACCESS_POLICY);

    private static final List<SyncCommandType> ACCESS_TYPES = List.of(
            SyncCommandType.DISABLE_USER, SyncCommandType.ENABLE_USER,
            SyncCommandType.UPDATE_VALIDITY, SyncCommandType.UPDATE_ACCESS_POLICY);

    private static final List<SyncCommandType> FACE_TYPES =
            List.of(SyncCommandType.UPSERT_FACE, SyncCommandType.DELETE_FACE, SyncCommandType.ENROLL_FACE);

    /** Everything a newer removal makes pointless to deliver. */
    private static final List<SyncCommandType> MEMBER_WRITES = List.of(
            SyncCommandType.CREATE_USER, SyncCommandType.UPDATE_USER, SyncCommandType.DISABLE_USER,
            SyncCommandType.ENABLE_USER, SyncCommandType.UPDATE_VALIDITY, SyncCommandType.UPDATE_ACCESS_POLICY,
            SyncCommandType.REMOVE_USER, SyncCommandType.UPSERT_FACE, SyncCommandType.DELETE_FACE,
            SyncCommandType.ENROLL_FACE, SyncCommandType.REPORT_DEVICE_USER);

    private boolean othersOpen(DeviceSyncCommand command, List<SyncCommandType> types) {
        if (command.getMemberId() == null) {
            return false;
        }
        return commandRepository.findByDeviceIdAndMemberIdAndTypeInAndStateIn(
                        command.getDeviceId(), command.getMemberId(), types, OPEN_STATES)
                .stream().anyMatch(c -> !c.getId().equals(command.getId()));
    }

    /** A manually cancelled command leaves the device's copy unconfirmed unless other work is queued. */
    private void settleAfterCancel(DeviceSyncCommand command) {
        MemberDeviceMapping mapping = faceMapping(command);
        if (mapping == null) {
            return;
        }
        if (isFaceCommand(command)) {
            if (!othersOpen(command, FACE_TYPES) && mapping.getFaceSyncState() == DeviceSyncState.PENDING) {
                mapping.setFaceSyncState(mapping.getFaceVersionSynced() == null
                        ? DeviceSyncState.NOT_SYNCED : DeviceSyncState.SYNCED);
                mappingRepository.save(mapping);
            }
        } else if (USER_TYPES.contains(command.getType())
                && !othersOpen(command, USER_TYPES) && mapping.getSyncState() == DeviceSyncState.PENDING) {
            mapping.setSyncState(DeviceSyncState.NOT_SYNCED);
            mappingRepository.save(mapping);
            refreshMembershipSyncState(command.getMemberId());
        }
    }

    /**
     * Adds the server's change time for the fields a member command carries. Gateways apply a
     * command only if it is newer than their own change of those fields (latest change wins), so
     * an old queued command can never overwrite a newer device edit.
     */
    private Map<String, Object> withChangeTimes(Long memberId, SyncCommandType type, Map<String, Object> payload) {
        if (memberId == null || payload == null) {
            return payload;
        }
        String field = switch (type) {
            case CREATE_USER, UPDATE_USER, UPDATE_ACCESS_POLICY -> "nameChangedAt";
            case UPDATE_VALIDITY, ENABLE_USER, DISABLE_USER -> "accessChangedAt";
            case UPSERT_FACE, DELETE_FACE -> "faceChangedAt";
            case REMOVE_USER -> "deletedAt";
            default -> null;
        };
        if (field == null) {
            return payload;
        }
        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null) {
            return payload;
        }
        Instant fallback = member.getProfileChangedAt() != null ? member.getProfileChangedAt()
                : member.getCreatedAt() != null ? member.getCreatedAt() : Instant.now();
        Instant at = switch (field) {
            case "nameChangedAt" -> member.getProfileChangedAt();
            case "faceChangedAt" -> member.getFaceChangedAt();
            default -> member.getAccessChangedAt();
        };
        Map<String, Object> stamped = new LinkedHashMap<>(payload);
        stamped.put(field, (at != null ? at : fallback).toString());
        return stamped;
    }

    private static boolean isFaceCommand(DeviceSyncCommand command) {
        return command.getType() == SyncCommandType.UPSERT_FACE
                || command.getType() == SyncCommandType.DELETE_FACE;
    }

    /**
     * Records the face version a device now holds. Results for a version older than what the
     * device is already known to hold are ignored.
     */
    private void applyFaceSuccess(DeviceSyncCommand command) {
        MemberDeviceMapping mapping = faceMapping(command);
        if (mapping == null) {
            return;
        }
        boolean moreQueued = othersOpen(command, FACE_TYPES);
        if (command.getType() == SyncCommandType.DELETE_FACE) {
            mapping.setFaceVersionSynced(null);
            mapping.setFaceSyncState(moreQueued ? DeviceSyncState.PENDING : DeviceSyncState.NOT_SYNCED);
        } else {
            Integer version = payloadInt(command, "faceVersion");
            Integer held = mapping.getFaceVersionSynced();
            boolean stale = version == null || (held != null && version < held);
            if (!stale) {
                mapping.setFaceVersionSynced(version);
            }
            boolean holdsFace = mapping.getFaceVersionSynced() != null;
            mapping.setFaceSyncState(moreQueued ? DeviceSyncState.PENDING
                    : holdsFace ? DeviceSyncState.SYNCED : DeviceSyncState.NOT_SYNCED);
        }
        mapping.setFaceLastError(null);
        mappingRepository.save(mapping);
    }

    private MemberDeviceMapping faceMapping(DeviceSyncCommand command) {
        if (command.getMemberId() == null) {
            return null;
        }
        return mappingRepository.findByDeviceIdAndMemberId(command.getDeviceId(), command.getMemberId())
                .orElse(null);
    }

    private Integer payloadInt(DeviceSyncCommand command, String field) {
        if (command.getPayload() == null) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(command.getPayload()).get(field);
            return node == null || node.isNull() ? null : node.asInt();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String payloadText(DeviceSyncCommand command, String field) {
        if (command.getPayload() == null) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(command.getPayload()).get(field);
            return node == null || node.isNull() ? null : node.asString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void recordSkipped(DeviceSyncCommand command, String reason) {
        String memberPublicId = command.getMemberId() == null ? null
                : memberRepository.findById(command.getMemberId()).map(Member::getPublicId).orElse(null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("command", command.getType().name());
        details.put("deviceId", command.getDeviceId());
        details.put("reason", reason == null ? "" : reason);
        auditService.recordSystem(AuditActions.SYNC_CHANGE_IGNORED, AuditActions.RESULT_SUCCESS,
                memberPublicId == null ? "DeviceSyncCommand" : "Member",
                memberPublicId == null ? command.getPublicId() : memberPublicId,
                command.getTenantId(), "gateway", details);
        log.info("Gateway skipped {} for member {}: {}", command.getType(), memberPublicId, reason);
    }

    private void publishMemberSync(DeviceSyncCommand command) {
        if (command.getMemberId() == null) {
            return;
        }
        memberRepository.findById(command.getMemberId()).ifPresent(member -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("memberId", member.getPublicId());
            payload.put("commandType", command.getType().name());
            payload.put("state", command.getState().name());
            events.publishEvent(new StaffLiveBroadcast(command.getTenantId(), "MEMBER_SYNC", payload));
        });
    }

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
        if (isFaceCommand(command)) {
            MemberDeviceMapping mapping = faceMapping(command);
            if (mapping != null) {
                mapping.setFaceLastError(error);
                if (command.getAttemptCount() >= command.getMaxAttempts()) {
                    mapping.setFaceSyncState(DeviceSyncState.FAILED);
                }
                mappingRepository.save(mapping);
            }
        }
        if (command.getAttemptCount() >= command.getMaxAttempts()) {
            command.setState(SyncCommandState.DEAD_LETTER);
            command.setCompletedAt(Instant.now());
            markState(command, DeviceSyncState.FAILED);
            FlowLog.warn("device", "command dead-lettered correlation={} type={} attempts={} error={}",
                    command.getCorrelationId(), command.getType(), command.getAttemptCount(), error);
            log.warn("Command {} dead-lettered after {} attempts: {}",
                    command.getCorrelationId(), command.getAttemptCount(), error);
        } else {
            command.setState(SyncCommandState.RETRYING);
            command.setNextAttemptAt(Instant.now().plus(backoff(command.getAttemptCount())));
            FlowLog.info("sync", "{} failed corr={} device={} member={} attempt {}/{}, retry at {}: {}",
                    command.getType(), command.getCorrelationId(), command.getDeviceId(), command.getMemberId(),
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

    /**
     * Propagates sync state to the member-device mapping for this device only. Membership
     * {@code device_sync_state} is derived: SYNCED only when every mapping for the member is SYNCED.
     */
    private void markState(DeviceSyncCommand command, DeviceSyncState state) {
        if (isFaceCommand(command)) {
            if (state == DeviceSyncState.PENDING || state == DeviceSyncState.FAILED) {
                MemberDeviceMapping mapping = faceMapping(command);
                if (mapping != null) {
                    mapping.setFaceSyncState(state);
                    mappingRepository.save(mapping);
                }
            }
            return;
        }
        if (command.getMemberId() != null) {
            DeviceSyncState applied = state == DeviceSyncState.SYNCED && othersOpen(command, USER_TYPES)
                    ? DeviceSyncState.PENDING : state;
            for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(command.getMemberId())) {
                if (mapping.getDeviceId().equals(command.getDeviceId())) {
                    mapping.setSyncState(applied);
                    mappingRepository.save(mapping);
                }
            }
            refreshMembershipSyncState(command.getMemberId());
        } else if (command.getMembershipId() != null) {
            Membership membership = membershipRepository.findById(command.getMembershipId()).orElse(null);
            if (membership != null) {
                refreshMembershipSyncState(membership.getMemberId());
            }
        }
    }

    /**
     * Membership {@code device_sync_state} summarises every device of the member, so it is
     * recomputed for all of the member's memberships whenever any mapping changes, whatever
     * command caused it.
     */
    private void refreshMembershipSyncState(Long memberId) {
        if (memberId == null) {
            return;
        }
        List<Membership> memberships = membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(memberId);
        if (memberships.isEmpty()) {
            return;
        }
        List<MemberDeviceMapping> mappings = mappingRepository.findByMemberId(memberId);
        DeviceSyncState summary;
        if (mappings.isEmpty()) {
            summary = DeviceSyncState.NOT_SYNCED;
        } else if (mappings.stream().allMatch(m -> m.getSyncState() == DeviceSyncState.SYNCED)) {
            summary = DeviceSyncState.SYNCED;
        } else if (mappings.stream().anyMatch(m -> m.getSyncState() == DeviceSyncState.FAILED)) {
            summary = DeviceSyncState.FAILED;
        } else {
            summary = DeviceSyncState.PENDING;
        }
        for (Membership membership : memberships) {
            if (membership.getDeviceSyncState() != summary) {
                membership.setDeviceSyncState(summary);
                membershipRepository.save(membership);
            }
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
