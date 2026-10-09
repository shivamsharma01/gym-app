package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.DeviceRole;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.dto.DeviceRequests.CreateDevice;
import com.example.gym.device.dto.DeviceRequests.UpdateDevice;
import com.example.gym.device.repo.AttendanceSyncCursorRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.domain.AttendanceSyncCursor;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.tenant.TenantGuard;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DeviceService {

    /** How long a matching checksum may stand in for the full comparison before the list is compared again. */
    static final Duration FULL_ROSTER_CHECK_EVERY = Duration.ofHours(24);

    private final DeviceRepository deviceRepository;
    private final GatewayService gatewayService;
    private final DeviceSyncService deviceSyncService;
    private final AttendanceSyncCursorRepository cursorRepository;
    private final AuditService auditService;

    public DeviceService(DeviceRepository deviceRepository,
                         GatewayService gatewayService,
                         DeviceSyncService deviceSyncService,
                         AttendanceSyncCursorRepository cursorRepository,
                         AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.gatewayService = gatewayService;
        this.deviceSyncService = deviceSyncService;
        this.cursorRepository = cursorRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<Device> list(Long tenantId, Pageable pageable) {
        return deviceRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public Device getByPublicId(String publicId, Long tenantId) {
        Device device = deviceRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(device.getTenantId(), tenantId, "Device");
        return device;
    }

    @Transactional
    public Device create(CreateDevice request, Long tenantId) {
        Device device = new Device(tenantId, request.name(),
                request.role() == null ? DeviceRole.UNSPECIFIED : request.role());
        device.setHost(request.host());
        device.setPort(request.port());
        device.setModel(request.model());
        device.setSerialNumber(request.serialNumber());
        device.setGatewayId(resolveGatewayId(request.gatewayId(), tenantId));
        Device saved = deviceRepository.save(device);
        FlowLog.info("device", "created id={} name={} role={}", saved.getPublicId(), saved.getName(), saved.getRole());
        auditService.record(AuditActions.DEVICE_CREATED, AuditActions.RESULT_SUCCESS,
                "Device", saved.getPublicId(), Map.of("name", saved.getName(), "role", saved.getRole().name()));
        return saved;
    }

    @Transactional
    public Device update(String publicId, UpdateDevice request, Long tenantId) {
        Device device = getByPublicId(publicId, tenantId);
        device.setName(request.name());
        device.setRole(request.role());
        device.setHost(request.host());
        device.setPort(request.port());
        device.setModel(request.model());
        device.setSerialNumber(request.serialNumber());
        device.setGatewayId(resolveGatewayId(request.gatewayId(), tenantId));
        Device saved = deviceRepository.save(device);
        FlowLog.info("device", "updated id={} name={}", saved.getPublicId(), saved.getName());
        auditService.record(AuditActions.DEVICE_UPDATED, AuditActions.RESULT_SUCCESS,
                "Device", saved.getPublicId(), Map.of("name", saved.getName()));
        return saved;
    }

    /** Updates connectivity/metadata reported by the gateway (no tenant context; gateway callback). */
    @Transactional
    public void updateConnection(Device device, DeviceConnectionState state, String firmware,
                                 String model, Instant lastSeen) {
        if (state != null) {
            device.setConnectionState(state);
        }
        if (StringUtils.hasText(firmware)) {
            device.setFirmware(firmware);
        }
        if (StringUtils.hasText(model)) {
            device.setModel(model);
        }
        device.setLastSeenAt(lastSeen == null ? Instant.now() : lastSeen);
        deviceRepository.save(device);
    }

    /**
     * Members are placed on a reader by a desired revision. This endpoint does not write a reader.
     */
    @Transactional
    public MemberDeviceMapping createMapping(String devicePublicId, String memberPublicId,
                                             String requestedDeviceUserId, Long tenantId) {
        getByPublicId(devicePublicId, tenantId);
        throw CommonExceptions.conflict(
                "A member is placed on a reader by a desired revision, not by a mapping command");
    }

    /**
     * Enqueues RECONCILE_DEVICE with attendance watermark so the gateway pulls history from the
     * last known point (not a hard-coded 24h window).
     */
    @Transactional
    public DeviceSyncCommand reconcile(String devicePublicId, Long tenantId) {
        Device device = getByPublicId(devicePublicId, tenantId);
        return enqueueReconcile(device, tenantId, true, false);
    }

    /**
     * Full Sync Now: attendance + user reconciliation via RECONCILE_DEVICE, and the gateway re-reads
     * every photo on the reader in the background (only changed photos come back).
     */
    @Transactional
    public DeviceSyncCommand syncNow(String devicePublicId, Long tenantId) {
        Device device = getByPublicId(devicePublicId, tenantId);
        DeviceSyncCommand command = enqueueReconcile(device, tenantId, true, true);
        auditService.record(AuditActions.DEVICE_SYNC_NOW_REQUESTED, AuditActions.RESULT_SUCCESS,
                "Device", device.getPublicId(), null);
        return command;
    }

    /** Enqueue reconcile if none is already in flight (used on gateway connect / device reconnect). */
    @Transactional
    public void enqueueReconcileIfAbsent(Device device) {
        if (deviceSyncService.hasActiveReconcile(device.getId())) {
            return;
        }
        enqueueReconcile(device, device.getTenantId(), false, false);
    }

    private DeviceSyncCommand enqueueReconcile(Device device, Long tenantId, boolean audit, boolean refreshFaces) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (refreshFaces) {
            payload.put("refreshFaces", true);
        }
        AttendanceSyncCursor cursor = cursorRepository.findByDeviceId(device.getId()).orElse(null);
        if (cursor != null) {
            if (cursor.getLastRecNo() != null) {
                payload.put("afterRecNo", cursor.getLastRecNo());
            }
            if (cursor.getLastEventAt() != null) {
                // Small overlap so clock skew / composite→rec upgrade still matches
                Instant from = cursor.getLastEventAt().minus(5, ChronoUnit.MINUTES);
                payload.put("fromUtc", from.toString());
            }
        }
        if (!payload.containsKey("fromUtc")) {
            payload.put("fromUtc", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        }
        payload.put("toUtc", Instant.now().plus(1, ChronoUnit.HOURS).toString());
        // With the checksum the reader had at the last full comparison, an unchanged reader skips sending its list.
        // Sync Now and the daily check always compare the full list.
        if (!refreshFaces) {
            deviceRepository.findRosterState(device.getId())
                    .filter(s -> s.getRosterDigest() != null && s.getRosterComparedAt() != null
                            && s.getRosterComparedAt().isAfter(Instant.now().minus(FULL_ROSTER_CHECK_EVERY)))
                    .ifPresent(s -> payload.put("knownDigest", s.getRosterDigest()));
        }

        DeviceSyncCommand command = deviceSyncService.enqueue(tenantId, device.getId(), null, null,
                SyncCommandType.RECONCILE_DEVICE, payload);
        if (audit) {
            auditService.record(AuditActions.DEVICE_RECONCILE_REQUESTED, AuditActions.RESULT_SUCCESS,
                    "Device", device.getPublicId(), null);
        }
        return command;
    }

    @Transactional
    public DeviceSyncCommand remoteDoor(String devicePublicId, String action, boolean confirmed,
                                        String reason, Long tenantId) {
        if (!confirmed) {
            throw CommonExceptions.badRequest("Remote door control requires confirmed=true");
        }
        SyncCommandType type = switch (action == null ? "" : action.toUpperCase()) {
            case "OPEN" -> SyncCommandType.OPEN_DOOR;
            case "CLOSE" -> SyncCommandType.CLOSE_DOOR;
            default -> throw CommonExceptions.badRequest("action must be OPEN or CLOSE");
        };
        Device device = getByPublicId(devicePublicId, tenantId);
        DeviceSyncCommand command = deviceSyncService.enqueue(tenantId, device.getId(), null, null,
                type, Map.of("action", action.toUpperCase(), "reason", reason == null ? "" : reason));
        auditService.record(AuditActions.DEVICE_REMOTE_DOOR, AuditActions.RESULT_SUCCESS,
                "Device", device.getPublicId(),
                Map.of("action", action.toUpperCase(), "reason", reason == null ? "" : reason));
        return command;
    }

    private Long resolveGatewayId(String gatewayPublicId, Long tenantId) {
        if (!StringUtils.hasText(gatewayPublicId)) {
            return null;
        }
        Gateway gateway = gatewayService.getByPublicId(gatewayPublicId, tenantId);
        return gateway.getId();
    }
}
