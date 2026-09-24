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
import com.example.gym.membership.DeviceSyncState;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.tenant.TenantGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public DeviceSyncService(DeviceSyncCommandRepository commandRepository,
                             MemberDeviceMappingRepository mappingRepository,
                             MembershipRepository membershipRepository,
                             GatewayCommandTransport transport,
                             GatewayProperties properties,
                             AuditService auditService,
                             JsonMapper jsonMapper) {
        this.commandRepository = commandRepository;
        this.mappingRepository = mappingRepository;
        this.membershipRepository = membershipRepository;
        this.transport = transport;
        this.properties = properties;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Enqueues a command. Intended to run inside the business transaction that caused it (true
     * transactional outbox), so the change and its command commit atomically.
     */
    @Transactional
    public DeviceSyncCommand enqueue(Long tenantId, Long deviceId, Long memberId, Long membershipId,
                                     SyncCommandType type, Map<String, Object> payload) {
        String correlationId = UUID.randomUUID().toString();
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
            boolean sent;
            try {
                sent = transport.dispatch(command);
            } catch (RuntimeException ex) {
                log.warn("Transport error dispatching {}: {}", command.getCorrelationId(), ex.getMessage());
                sent = false;
            }
            if (sent) {
                command.setState(SyncCommandState.DISPATCHED);
                command.setDispatchedAt(Instant.now());
                command.setAttemptCount(command.getAttemptCount() + 1);
                dispatched++;
            } else {
                failAttempt(command, "Gateway not reachable");
            }
            commandRepository.save(command);
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
            return;
        }
        command.setAcknowledgedAt(Instant.now());
        if (ok) {
            command.setState(SyncCommandState.SUCCEEDED);
            command.setCompletedAt(Instant.now());
            command.setLastError(null);
            markState(command, DeviceSyncState.SYNCED);
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
            Long tenantId, Long deviceId, org.springframework.data.domain.Pageable pageable) {
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
                deviceId,
                SyncCommandType.RECONCILE_DEVICE,
                List.of(SyncCommandState.PENDING, SyncCommandState.DISPATCHED,
                        SyncCommandState.ACKNOWLEDGED, SyncCommandState.RETRYING));
    }

    // --- internals -------------------------------------------------------------------------------

    private void failAttempt(DeviceSyncCommand command, String error) {
        command.setAttemptCount(command.getAttemptCount() + 1);
        command.setLastError(truncate(error, 500));
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
