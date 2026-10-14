package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderRevisionRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-reader view of this member's desired revision and whether that reader applied it, plus a per-reader republish. */
@Service
public class MemberDeviceSyncService {

    private final MemberService memberService;
    private final MemberFaceRepository faceRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final DeviceRepository deviceRepository;
    private final DesiredMemberProjectionRepository projections;
    private final ReaderRevisionRepository revisions;
    private final DesiredProjectionService desired;

    public MemberDeviceSyncService(MemberService memberService,
                                   MemberFaceRepository faceRepository,
                                   MemberDeviceMappingRepository mappingRepository,
                                   DeviceRepository deviceRepository,
                                   DesiredMemberProjectionRepository projections,
                                   ReaderRevisionRepository revisions,
                                   DesiredProjectionService desired) {
        this.memberService = memberService;
        this.faceRepository = faceRepository;
        this.mappingRepository = mappingRepository;
        this.deviceRepository = deviceRepository;
        this.projections = projections;
        this.revisions = revisions;
        this.desired = desired;
    }

    /**
     * Removes the member from this reader only, as a desired absence. The member stays, and no other
     * reader is written.
     */
    @Transactional
    public void removeFromReader(String memberPublicId, String devicePublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        Device device = deviceRepository.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Device"));
        TenantGuard.check(device.getTenantId(), tenantId, "Device");
        if (mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId()).isEmpty()) {
            return;
        }
        desired.publishRemoval(member, device);
    }

    /**
     * Each reader that has this member, with the member's desired revision there and that reader's
     * highest acknowledged revision. The member is on the reader as desired only when the gateway
     * acknowledged this row's current revision: revisions of different members can be applied out
     * of order, so a higher reader revision says nothing about this member. A held row waits for
     * staff; a failure the gateway reported is shown while that revision is current.
     */
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

        List<DeviceRow> rows = new ArrayList<>();
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            Device device = devices.get(mapping.getDeviceId());
            if (device == null) {
                continue;
            }
            DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                    .orElse(null);
            long applied = revisions.findByDeviceId(device.getId())
                    .map(ReaderRevision::getAppliedRevision)
                    .orElse(0L);
            rows.add(new DeviceRow(
                    device.getPublicId(),
                    device.getName(),
                    device.getConnectionState() == null ? null : device.getConnectionState().name(),
                    device.getGatewayId() != null,
                    mapping.getDeviceUserId(),
                    row == null ? null : row.getRevision(),
                    applied,
                    row != null && row.isApplied(),
                    row != null && row.isPresentOnReader(),
                    row != null && row.isPresentOnReader() && row.isFacePresent(),
                    row != null && row.isHeld(),
                    row == null ? null : row.getApplyError(),
                    row == null ? null : row.getApplyAttempts(),
                    row != null && row.isApplyErrorPermanent()));
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
            return;
        }
        desired.republish(member, device);
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
            /** This member's desired revision on this reader; null when none was published. */
            Long revision,
            long appliedRevision,
            boolean applied,
            boolean present,
            boolean facePresent,
            /** The reader showed something else; nothing is written until staff decide. */
            boolean held,
            /** Why the gateway has not applied the current revision; null when it reported nothing. */
            String applyError,
            Integer applyAttempts,
            /** The gateway cannot apply the current revision as sent; it stays until replaced. */
            boolean applyErrorPermanent) {
    }
}
