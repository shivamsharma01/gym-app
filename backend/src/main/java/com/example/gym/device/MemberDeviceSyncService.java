package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-device view of where a member (and their face) has reached, plus a per-device retry. */
@Service
public class MemberDeviceSyncService {

    private final MemberService memberService;
    private final MemberFaceRepository faceRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceSyncCommandRepository commandRepository;
    private final MemberDeviceProvisioningService provisioning;

    public MemberDeviceSyncService(MemberService memberService,
                                   MemberFaceRepository faceRepository,
                                   MemberDeviceMappingRepository mappingRepository,
                                   DeviceRepository deviceRepository,
                                   DeviceSyncCommandRepository commandRepository,
                                   MemberDeviceProvisioningService provisioning) {
        this.memberService = memberService;
        this.faceRepository = faceRepository;
        this.mappingRepository = mappingRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.provisioning = provisioning;
    }

    @Transactional(readOnly = true)
    public SyncStatus status(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        Map<Long, Device> devices = deviceRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        MemberFace face = faceRepository.findByMemberId(member.getId()).orElse(null);
        FaceInfo faceInfo = face == null ? null : new FaceInfo(
                face.getFaceVersion(),
                face.getSource().name(),
                face.getSourceDeviceId() == null || !devices.containsKey(face.getSourceDeviceId()) ? null
                        : devices.get(face.getSourceDeviceId()).getName(),
                face.getChangedAt());
        Map<Long, List<DeviceSyncCommand>> open = commandRepository
                .findByMemberIdAndStateIn(member.getId(), DeviceSyncService.OPEN_STATES).stream()
                .collect(Collectors.groupingBy(DeviceSyncCommand::getDeviceId));

        List<DeviceRow> rows = new ArrayList<>();
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            Device device = devices.get(mapping.getDeviceId());
            if (device == null) {
                continue;
            }
            List<OpenCommand> commands = open.getOrDefault(device.getId(), List.of()).stream()
                    .map(c -> new OpenCommand(c.getType().name(), c.getState().name(), c.getAttemptCount(),
                            c.getLastError()))
                    .toList();
            rows.add(new DeviceRow(
                    device.getPublicId(),
                    device.getName(),
                    device.getConnectionState() == null ? null : device.getConnectionState().name(),
                    device.getGatewayId() != null,
                    mapping.getDeviceUserId(),
                    mapping.getPendingDeviceUserId(),
                    member.getSerialNumber() != null && !member.getSerialNumber().equals(mapping.getDeviceUserId()),
                    mapping.getSyncState() == null ? null : mapping.getSyncState().name(),
                    mapping.getFaceSyncState() == null ? null : mapping.getFaceSyncState().name(),
                    mapping.getFaceVersionSynced(),
                    mapping.getFaceLastError(),
                    commands));
        }
        return new SyncStatus(faceInfo, rows);
    }

    /** Re-sends name, authorization and face for this member to one device. */
    @Transactional
    public void retry(String memberPublicId, String devicePublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        Device device = deviceRepository.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(device.getTenantId(), tenantId, "Device");
        if (mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId()).isEmpty()) {
            provisioning.provisionMember(member, Set.of());
            return;
        }
        provisioning.repushUser(member, device.getId());
        if (faceRepository.findByMemberId(member.getId()).isPresent()) {
            provisioning.repushFace(member, device.getId());
        }
    }

    /**
     * Asks the gateway to read this member's name, access and photo fresh from one device. A device copy
     * newer than the server's is applied; an older one is replaced by the server's.
     */
    @Transactional
    public void readFromDevice(String memberPublicId, String devicePublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        Device device = deviceRepository.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(device.getTenantId(), tenantId, "Device");
        MemberDeviceMapping mapping = mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElseThrow(() -> CommonExceptions.notFound("Member on this device"));
        provisioning.requestDeviceReport(member, device, mapping.getDeviceUserId());
    }

    public record SyncStatus(FaceInfo face, List<DeviceRow> devices) {
    }

    public record FaceInfo(int version, String source, String sourceDeviceName, Instant changedAt) {
    }

    public record DeviceRow(
            String deviceId,
            String deviceName,
            String connectionState,
            boolean hasGateway,
            String deviceUserId,
            /** The serial this reader is moving the member to; null when not moving. */
            String pendingDeviceUserId,
            /** True when this reader holds the member under an id other than their serial. */
            boolean differsFromSerial,
            String userSyncState,
            String faceSyncState,
            Integer faceVersionSynced,
            String faceLastError,
            List<OpenCommand> openCommands) {
    }

    public record OpenCommand(String type, String state, int attemptCount, String lastError) {
    }
}
