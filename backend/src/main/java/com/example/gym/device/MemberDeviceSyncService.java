package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceConnectionState;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderRevisionRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final DeviceReviewItemRepository reviewItemRepository;
    private final DesiredProjectionService desired;
    private final ReaderFacePresence seenOnReader;
    private final DeviceReachability reachability;

    public MemberDeviceSyncService(MemberService memberService,
                                   MemberFaceRepository faceRepository,
                                   MemberDeviceMappingRepository mappingRepository,
                                   DeviceRepository deviceRepository,
                                   DesiredMemberProjectionRepository projections,
                                   ReaderRevisionRepository revisions,
                                   DeviceReviewItemRepository reviewItemRepository,
                                   DesiredProjectionService desired,
                                   ReaderFacePresence seenOnReader,
                                   DeviceReachability reachability) {
        this.memberService = memberService;
        this.faceRepository = faceRepository;
        this.mappingRepository = mappingRepository;
        this.deviceRepository = deviceRepository;
        this.projections = projections;
        this.revisions = revisions;
        this.reviewItemRepository = reviewItemRepository;
        this.desired = desired;
        this.seenOnReader = seenOnReader;
        this.reachability = reachability;
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
     * Every reader in the tenant, with this member's device user id when mapped. The member is on
     * the reader as desired only when the gateway acknowledged this row's current revision:
     * revisions of different members can be applied out of order, so a higher reader revision says
     * nothing about this member. A held row waits for staff; a failure the gateway reported is
     * shown while that revision is current.
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

        Map<Long, MemberDeviceMapping> byDevice = mappingRepository.findByMemberId(member.getId()).stream()
                .collect(Collectors.toMap(MemberDeviceMapping::getDeviceId, Function.identity(), (a, b) -> a));
        Set<Long> openReviewDevices = reviewItemRepository.findByMemberId(member.getId()).stream()
                .filter(item -> !item.isResolved())
                .map(DeviceReviewItem::getDeviceId)
                .collect(Collectors.toSet());
        Map<Long, ReaderFacePresence.Sight> seenByDevice = seenOnReader
                .forMembers(tenantId, List.of(member.getId()))
                .getOrDefault(member.getId(), Map.of());
        Map<Long, DeviceConnectionState> connection = reachability.shown(devices.values());
        List<DeviceRow> rows = new ArrayList<>();
        for (Device device : devices.values().stream()
                .sorted(Comparator.comparing(Device::getName, String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            MemberDeviceMapping mapping = byDevice.get(device.getId());
            DesiredMemberProjection row = mapping == null ? null
                    : projections.findByDeviceIdAndMemberId(device.getId(), member.getId()).orElse(null);
            long applied = revisions.findByDeviceId(device.getId())
                    .map(ReaderRevision::getAppliedRevision)
                    .orElse(0L);
            boolean held = (row != null && row.isHeld()) || openReviewDevices.contains(device.getId());
            ReaderFacePresence.Sight seen = mapping == null ? seenByDevice.get(device.getId()) : null;
            boolean projected = row != null && row.isPresentOnReader();
            rows.add(new DeviceRow(
                    device.getPublicId(),
                    device.getName(),
                    connection.get(device.getId()).name(),
                    device.getGatewayId() != null,
                    mapping == null ? null : mapping.getDeviceUserId(),
                    row == null ? null : row.getRevision(),
                    applied,
                    row != null && row.isApplied(),
                    projected,
                    (row != null && row.isPresentOnReader() && row.isFacePresent())
                            || (seen != null && seen.facePresent()),
                    held,
                    row == null ? null : row.getApplyError(),
                    row == null ? null : row.getApplyAttempts(),
                    row != null && row.isApplyErrorPermanent(),
                    row != null && row.isPresentOnReader() ? row.getValidFrom() : seen == null ? null : seen.validFrom(),
                    row != null && row.isPresentOnReader() ? row.getValidTo() : seen == null ? null : seen.validTo(),
                    seen == null ? null : seen.deviceUserId()));
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
            /** Null when this member has no mapping on that reader yet. */
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
            boolean applyErrorPermanent,
            /** Desired validity on this reader (reader-local); the reader's own dates when only seen. */
            String validFrom,
            String validTo,
            /**
             * The user id this reader already shows for this member's photo, when there is no mapping.
             * Null once the member is mapped here, and null when the photo is not on the reader.
             */
            String seenDeviceUserId) {
    }
}
