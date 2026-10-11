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
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.dto.DeviceRequests.CreateDevice;
import com.example.gym.device.dto.DeviceRequests.UpdateDevice;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderRevisionRepository;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
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
    private final DeviceSyncService deviceSyncService;
    private final AuditService auditService;
    private final ReaderRevisionRepository revisions;

    public DeviceService(DeviceRepository deviceRepository,
                         GatewayService gatewayService,
                         DeviceSyncService deviceSyncService,
                         AuditService auditService,
                         ReaderRevisionRepository revisions) {
        this.deviceRepository = deviceRepository;
        this.gatewayService = gatewayService;
        this.deviceSyncService = deviceSyncService;
        this.auditService = auditService;
        this.revisions = revisions;
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
        revisions.save(new ReaderRevision(tenantId, saved.getId()));
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
