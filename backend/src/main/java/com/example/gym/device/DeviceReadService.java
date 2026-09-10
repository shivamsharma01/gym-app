package com.example.gym.device;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.AttendanceSyncCursor;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.SecurityEvent;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.dto.DeviceResponses.DeviceHealth;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.AttendanceSyncCursorRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.SecurityEventRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side queries for attendance, security events, and device health (tenant-scoped). */
@Service
public class DeviceReadService {

    private static final List<SyncCommandState> PENDING_STATES = List.of(
            SyncCommandState.PENDING, SyncCommandState.DISPATCHED, SyncCommandState.ACKNOWLEDGED,
            SyncCommandState.RETRYING, SyncCommandState.FAILED);

    private final AttendanceEventRepository attendanceRepository;
    private final AttendanceSyncCursorRepository cursorRepository;
    private final SecurityEventRepository securityEventRepository;
    private final DeviceSyncCommandRepository commandRepository;
    private final GatewayRepository gatewayRepository;
    private final DeviceService deviceService;
    private final GatewaySessionRegistry sessionRegistry;
    private final MemberService memberService;

    public DeviceReadService(AttendanceEventRepository attendanceRepository,
                             AttendanceSyncCursorRepository cursorRepository,
                             SecurityEventRepository securityEventRepository,
                             DeviceSyncCommandRepository commandRepository,
                             GatewayRepository gatewayRepository,
                             DeviceService deviceService,
                             GatewaySessionRegistry sessionRegistry,
                             MemberService memberService) {
        this.attendanceRepository = attendanceRepository;
        this.cursorRepository = cursorRepository;
        this.securityEventRepository = securityEventRepository;
        this.commandRepository = commandRepository;
        this.gatewayRepository = gatewayRepository;
        this.deviceService = deviceService;
        this.sessionRegistry = sessionRegistry;
        this.memberService = memberService;
    }

    @Transactional(readOnly = true)
    public Page<AttendanceEvent> attendance(Long tenantId, Pageable pageable) {
        return attendanceRepository.findByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceEvent> attendanceForMember(String memberPublicId, Long tenantId,
                                                     Pageable pageable) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        return attendanceRepository.findByTenantIdAndMemberIdOrderByOccurredAtDesc(
                tenantId, member.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<SecurityEvent> securityEvents(Long tenantId, Pageable pageable) {
        return securityEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId, pageable);
    }

    /**
     * Distinguishes device connectivity, gateway connectivity, last successful sync, and pending
     * work (§11) instead of a single “online/offline” flag.
     */
    @Transactional(readOnly = true)
    public DeviceHealth health(String devicePublicId, Long tenantId) {
        Device device = deviceService.getByPublicId(devicePublicId, tenantId);
        String gatewayStatus = "UNASSIGNED";
        boolean sessionOnline = false;
        if (device.getGatewayId() != null) {
            Gateway gateway = gatewayRepository.findById(device.getGatewayId()).orElse(null);
            if (gateway != null) {
                gatewayStatus = gateway.getStatus().name();
                sessionOnline = sessionRegistry.isOnline(gateway.getPublicId());
            }
        }
        Instant lastSync = commandRepository
                .findTopByDeviceIdAndStateOrderByCompletedAtDesc(device.getId(), SyncCommandState.SUCCEEDED)
                .map(DeviceSyncCommand::getCompletedAt)
                .orElse(null);
        long pending = commandRepository.countByDeviceIdAndStateIn(device.getId(), PENDING_STATES);
        AttendanceSyncCursor cursor = cursorRepository.findByDeviceId(device.getId()).orElse(null);
        return new DeviceHealth(
                device.getPublicId(),
                device.getConnectionState().name(),
                gatewayStatus,
                sessionOnline,
                device.getLastSeenAt(),
                lastSync,
                pending,
                cursor == null ? null : cursor.getLastRecNo(),
                cursor == null ? null : cursor.getLastEventAt());
    }
}
