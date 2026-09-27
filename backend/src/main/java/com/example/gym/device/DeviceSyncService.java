package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
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
    private final MemberDeviceMappingRepository mappingRepository;
    private final MembershipRepository membershipRepository;
    private final GatewayCommandTransport transport;
    private final GatewayProperties properties;
    private final AuditService auditService;
    private final JsonMapper jsonMapper;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher events;

    public DeviceSyncService(DeviceSyncCommandRepository commandRepository,
                             MemberDeviceMappingRepository mappingRepository,
                             MembershipRepository membershipRepository,
                             GatewayCommandTransport transport,
                             GatewayProperties properties,
                             AuditService auditService,
                             JsonMapper jsonMapper,
                             MemberRepository memberRepository,
                             ApplicationEventPublisher events) {
        this.commandRepository = commandRepository;
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
        }
    }

    /**
     * Enqueues a command. Intended to run inside the business transaction that caused it (true
     * transactional outbox), so the change and its command commit atomically.
     */
    @Transactional
    public DeviceSyncCommand enqueue(Long tenantId, Long deviceId, Long memberId, Long membershipId,
                                     SyncCommandType type, Map<String, Object> payload) {
        String correlationId = UUID.randomUUID().toString();
        payload = withChangeTimes(memberId, type, payload);
        String payloadJson = payload == null ? null : jsonMapper.writeValueAsString(payload);
        DeviceSyncCommand command = new DeviceSyncCommand(tenantId, deviceId, memberId, membershipId,
                type, payloadJson, correlationId, properties.getOutbox().getMaxAttempts(), Instant.now());
        DeviceSyncCommand saved = commandRepository.save(command);

        markState(saved, DeviceSyncState.PENDING);
        auditService.record(AuditActions.DEVICE_SYNC_ENQUEUED, AuditActions.RESULT_SUCCESS,
                "DeviceSyncCommand", saved.getPublicId(), Map.of("type", type.name()));
        return saved;
    }

    /**
     * Reclaims DISPATCHED / ACKNOWLEDGED commands that never received SYNC_RESULT so they become
     * retryable again (gateway crash after WSS write, lost poll body, reconcile without SYNC_RESULT).
     */
    @Transactional
    public int reclaimStaleDispatched() {
        Duration timeout = properties.getOutbox().getDispatchTimeout();
        Instant cutoff = Instant.now().minus(timeout);
        List<DeviceSyncCommand> stale = commandRepository.findByStateInAndDispatchedAtLessThanEqual(
                List.of(SyncCommandState.DISPATCHED, SyncCommandState.ACKNOWLEDGED),
                cutoff,
                PageRequest.of(0, properties.getOutbox().getBatchSize()));
        for (DeviceSyncCommand command : stale) {
            command.setState(SyncCommandState.RETRYING);
            command.setNextAttemptAt(Instant.now());
            command.setLastError(truncate("Reclaimed: no SYNC_RESULT within " + timeout, 500));
            commandRepository.save(command);
            log.info("Reclaimed stale command {} (was {})", command.getCorrelationId(),
                    command.getDispatchedAt());
        }
        return stale.size();
    }

    /** Claims due commands and attempts delivery to their gateways. Returns the number dispatched. */
    @Transactional
    public int dispatchDue() {
        reclaimStaleDispatched();
        List<DeviceSyncCommand> due = commandRepository
                .findByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        List.of(SyncCommandState.PENDING, SyncCommandState.RETRYING),
                        Instant.now(),
                        PageRequest.of(0, properties.getOutbox().getBatchSize()));
        int dispatched = 0;
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
                }
                case NOT_CONNECTED -> waitForGateway(command);
                case FAILED -> failAttempt(command, "Delivery to the gateway failed");
            }
            commandRepository.save(command);
        }
        return dispatched;
    }

    /** Applies a gateway result. Idempotent: replays for already-terminal commands are ignored. */
    @Transactional
    public void handleResult(String correlationId, boolean ok, String error) {
        handleResult(correlationId, ok, error, false);
    }

    /**
     * {@code skipped}: the gateway did not apply the command because its devices hold a newer
     * change of the same fields (that change reaches the server as DEVICE_USER_CHANGED). The
     * command is complete; the skip is recorded so staff can see what was not applied.
     */
    @Transactional
    public void handleResult(String correlationId, boolean ok, String error, boolean skipped) {
        DeviceSyncCommand command = commandRepository.findByCorrelationId(correlationId).orElse(null);
        if (command == null) {
            log.debug("SYNC_RESULT for unknown correlationId {}", correlationId);
            return;
        }
        if (command.getState().isTerminal()) {
            return;
        }
        command.setAcknowledgedAt(Instant.now());
        if (ok && skipped) {
            command.setState(SyncCommandState.SUCCEEDED);
            command.setCompletedAt(Instant.now());
            command.setLastError(truncate("Skipped by gateway (newer change on device): " + error, 500));
            recordSkipped(command, error);
        } else if (ok) {
            command.setState(SyncCommandState.SUCCEEDED);
            command.setCompletedAt(Instant.now());
            command.setLastError(null);
            if (isFaceCommand(command)) {
                applyFaceSuccess(command);
            } else {
                markState(command, DeviceSyncState.SYNCED);
            }
        } else {
            failAttempt(command, error);
        }
        commandRepository.save(command);
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
        boolean moreQueued = commandRepository.findByDeviceIdAndMemberIdAndTypeInAndStateIn(
                        command.getDeviceId(), command.getMemberId(),
                        List.of(SyncCommandType.UPSERT_FACE, SyncCommandType.DELETE_FACE), OPEN_STATES)
                .stream().anyMatch(c -> !c.getId().equals(command.getId()));
        if (command.getType() == SyncCommandType.DELETE_FACE) {
            mapping.setFaceVersionSynced(null);
            mapping.setFaceSyncState(moreQueued ? DeviceSyncState.PENDING : DeviceSyncState.NOT_SYNCED);
        } else {
            Integer version = payloadInt(command, "faceVersion");
            Integer held = mapping.getFaceVersionSynced();
            if (version == null || (held != null && version < held)) {
                return;
            }
            mapping.setFaceVersionSynced(version);
            mapping.setFaceSyncState(moreQueued ? DeviceSyncState.PENDING : DeviceSyncState.SYNCED);
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
    }

    /** Makes every waiting command of the gateway's devices due now (called when it connects). */
    @Transactional
    public int wakeGateway(List<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return 0;
        }
        return commandRepository.makeDueNow(deviceIds,
                List.of(SyncCommandState.PENDING, SyncCommandState.RETRYING), Instant.now());
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
            log.warn("Command {} dead-lettered after {} attempts: {}",
                    command.getCorrelationId(), command.getAttemptCount(), error);
        } else {
            command.setState(SyncCommandState.RETRYING);
            command.setNextAttemptAt(Instant.now().plus(backoff(command.getAttemptCount())));
        }
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
            for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(command.getMemberId())) {
                if (mapping.getDeviceId().equals(command.getDeviceId())) {
                    mapping.setSyncState(state);
                    mappingRepository.save(mapping);
                }
            }
            refreshMembershipSyncState(command.getMembershipId(), command.getMemberId());
        } else if (command.getMembershipId() != null) {
            Membership membership = membershipRepository.findById(command.getMembershipId()).orElse(null);
            if (membership != null) {
                refreshMembershipSyncState(command.getMembershipId(), membership.getMemberId());
            }
        }
    }

    private void refreshMembershipSyncState(Long membershipId, Long memberId) {
        if (membershipId == null || memberId == null) {
            return;
        }
        Membership membership = membershipRepository.findById(membershipId).orElse(null);
        if (membership == null) {
            return;
        }
        List<MemberDeviceMapping> mappings = mappingRepository.findByMemberId(memberId);
        if (mappings.isEmpty()) {
            membership.setDeviceSyncState(DeviceSyncState.NOT_SYNCED);
            membershipRepository.save(membership);
            return;
        }
        boolean anyFailed = mappings.stream().anyMatch(m -> m.getSyncState() == DeviceSyncState.FAILED);
        boolean anyPending = mappings.stream().anyMatch(m ->
                m.getSyncState() == DeviceSyncState.PENDING
                        || m.getSyncState() == DeviceSyncState.NOT_SYNCED
                        || m.getSyncState() == DeviceSyncState.OFFLINE);
        boolean allSynced = mappings.stream().allMatch(m -> m.getSyncState() == DeviceSyncState.SYNCED);
        if (allSynced) {
            membership.setDeviceSyncState(DeviceSyncState.SYNCED);
        } else if (anyFailed) {
            membership.setDeviceSyncState(DeviceSyncState.FAILED);
        } else if (anyPending) {
            membership.setDeviceSyncState(DeviceSyncState.PENDING);
        } else {
            membership.setDeviceSyncState(DeviceSyncState.PENDING);
        }
        membershipRepository.save(membership);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
