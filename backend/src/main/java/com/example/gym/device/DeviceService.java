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

    private final DeviceRepository deviceRepository;
    private final GatewayService gatewayService;
    private final MemberDeviceMappingRepository mappingRepository;
    private final MemberService memberService;
    private final DeviceSyncService deviceSyncService;
    private final AttendanceSyncCursorRepository cursorRepository;
    private final AuditService auditService;
    private final MemberDeviceProvisioningService provisioning;
    private final MemberFaceRepository faceRepository;

    public DeviceService(DeviceRepository deviceRepository,
                         GatewayService gatewayService,
                         MemberDeviceMappingRepository mappingRepository,
                         MemberService memberService,
                         DeviceSyncService deviceSyncService,
                         AttendanceSyncCursorRepository cursorRepository,
                         AuditService auditService,
                         MemberDeviceProvisioningService provisioning,
                         MemberFaceRepository faceRepository) {
        this.provisioning = provisioning;
        this.faceRepository = faceRepository;
        this.deviceRepository = deviceRepository;
        this.gatewayService = gatewayService;
        this.mappingRepository = mappingRepository;
        this.memberService = memberService;
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
        provisioning.provisionDevice(saved);
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
        Long previousGatewayId = device.getGatewayId();
        device.setGatewayId(resolveGatewayId(request.gatewayId(), tenantId));
        Device saved = deviceRepository.save(device);
        if (saved.getGatewayId() != null && !saved.getGatewayId().equals(previousGatewayId)) {
            provisioning.provisionDevice(saved);
        }
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
     * Creates a member↔device mapping (enrolment intent) and seeds the outbox: a CREATE_USER command
     * and, when the member has a current membership, an UPDATE_VALIDITY. The device is not touched
     * here — the gateway applies the commands and reports the real result.
     */
    @Transactional
    public MemberDeviceMapping createMapping(String devicePublicId, String memberPublicId,
                                             String requestedDeviceUserId, Long tenantId) {
        Device device = getByPublicId(devicePublicId, tenantId);
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        final String deviceUserId = StringUtils.hasText(requestedDeviceUserId)
                ? requestedDeviceUserId.trim() : member.getDeviceUserId();

        if (mappingRepository.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            throw CommonExceptions.conflict("Member is already mapped to this device");
        }
        if (mappingRepository.existsByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)) {
            throw CommonExceptions.conflict("Device user id is already in use on this device");
        }

        MemberDeviceMapping mapping = mappingRepository.save(
                new MemberDeviceMapping(tenantId, member.getId(), device.getId(), deviceUserId));
        auditService.record(AuditActions.DEVICE_MAPPING_CREATED, AuditActions.RESULT_SUCCESS,
                "MemberDeviceMapping", mapping.getPublicId(),
                Map.of("member", member.getPublicId(), "device", device.getPublicId()));

        Map<String, Object> createPayload = new LinkedHashMap<>();
        createPayload.put("deviceUserId", deviceUserId);
        createPayload.put("memberCode", member.getMemberCode());
        createPayload.put("name", member.getFullName());
        deviceSyncService.enqueue(tenantId, device.getId(), member.getId(), null,
                SyncCommandType.CREATE_USER, createPayload);

        provisioning.pushAccess(member, device.getId(), deviceUserId);
        if (faceRepository.findByMemberId(member.getId()).isPresent()) {
            provisioning.repushFace(member, device.getId());
        }

        return mapping;
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
