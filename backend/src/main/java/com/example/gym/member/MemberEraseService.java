package com.example.gym.member;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.ReaderFacePresence;
import com.example.gym.device.ReaderReconcileService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.DeviceReviewSnapshotRepository;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.face.FaceStorageService;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.payment.PaymentRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a member from the gym and queues the same person off every reader. A reader id that
 * Review has paused, because that id shows someone else, is left on the reader.
 */
@Service
public class MemberEraseService {

    private final MemberService members;
    private final MemberRepository memberRepository;
    private final ReaderReconcileService readers;
    private final DesiredProjectionService desired;
    private final DesiredMemberProjectionRepository projections;
    private final DeviceRepository devices;
    private final ReaderFacePresence presence;
    private final DeviceObservedUserRepository observedUsers;
    private final MemberDeviceMappingRepository mappings;
    private final DeviceReviewItemRepository reviews;
    private final DeviceReviewSnapshotRepository snapshots;
    private final AttendanceEventRepository attendance;
    private final PaymentRepository payments;
    private final MembershipRepository memberships;
    private final MemberFaceRepository faces;
    private final FaceStorageService storage;
    private final AuditService auditService;

    public MemberEraseService(MemberService members,
                              MemberRepository memberRepository,
                              ReaderReconcileService readers,
                              DesiredProjectionService desired,
                              DesiredMemberProjectionRepository projections,
                              DeviceRepository devices,
                              ReaderFacePresence presence,
                              DeviceObservedUserRepository observedUsers,
                              MemberDeviceMappingRepository mappings,
                              DeviceReviewItemRepository reviews,
                              DeviceReviewSnapshotRepository snapshots,
                              AttendanceEventRepository attendance,
                              PaymentRepository payments,
                              MembershipRepository memberships,
                              MemberFaceRepository faces,
                              FaceStorageService storage,
                              AuditService auditService) {
        this.members = members;
        this.memberRepository = memberRepository;
        this.readers = readers;
        this.desired = desired;
        this.projections = projections;
        this.devices = devices;
        this.presence = presence;
        this.observedUsers = observedUsers;
        this.mappings = mappings;
        this.reviews = reviews;
        this.snapshots = snapshots;
        this.attendance = attendance;
        this.payments = payments;
        this.memberships = memberships;
        this.faces = faces;
        this.storage = storage;
        this.auditService = auditService;
    }

    @Transactional
    public void erase(String publicId, Long tenantId) {
        Member member = members.getByPublicId(publicId, tenantId);
        readers.lockReaders(member.getTenantId());
        Set<String> queued = new HashSet<>();
        for (DesiredMemberProjection row : projections.findByMemberId(member.getId())) {
            queueRemoval(member, row, queued);
        }
        queueSeenOnly(member, queued);
        projections.flush();
        mappings.deleteAll(mappings.findByMemberId(member.getId()));
        mappings.flush();
        for (DeviceReviewItem item : reviews.findByMemberId(member.getId())) {
            snapshots.deleteAll(snapshots.findByReviewItemId(item.getId()));
        }
        snapshots.flush();
        reviews.deleteAll(reviews.findByMemberId(member.getId()));
        reviews.flush();
        attendance.unlinkMember(member.getId());
        payments.deleteAll(payments.findByMemberIdOrderByPaidOnDescIdDesc(member.getId()));
        payments.flush();
        memberships.deleteAll(memberships.findByMemberId(member.getId()));
        memberships.flush();
        faces.findByMemberId(member.getId()).ifPresent(face -> {
            storage.deleteAfterCommit(face.getObjectKey());
            faces.delete(face);
        });
        faces.flush();
        memberRepository.delete(member);
        FlowLog.info("member", "erased id={}", publicId);
        auditService.record(AuditActions.MEMBER_ERASED, AuditActions.RESULT_SUCCESS,
                "Member", publicId, Map.of("memberCode", member.getMemberCode(), "name", member.getFullName()));
    }

    private void queueRemoval(Member member, DesiredMemberProjection row, Set<String> queued) {
        Device device = devices.findById(row.getDeviceId()).orElse(null);
        String deviceUserId = row.getDeviceUserId();
        if (device == null) {
            projections.delete(row);
            return;
        }
        if (row.isHeld()) {
            projections.delete(row);
            remember(queued, device.getId(), deviceUserId);
            return;
        }
        if (row.isPresentOnReader()) {
            desired.publishRemoval(member, device);
        }
        if (!row.isPresentOnReader() && row.isApplied()) {
            projections.delete(row);
        } else {
            row.setMemberId(null);
            row.setHeld(false);
            projections.save(row);
        }
        remember(queued, device.getId(), deviceUserId);
    }

    /** Same photo already on a reader, with no mapping of its own. */
    private void queueSeenOnly(Member member, Set<String> queued) {
        Map<Long, ReaderFacePresence.Sight> byDevice = presence
                .forMembers(member.getTenantId(), List.of(member.getId()))
                .getOrDefault(member.getId(), Map.of());
        for (Map.Entry<Long, ReaderFacePresence.Sight> entry : byDevice.entrySet()) {
            String deviceUserId = entry.getValue().deviceUserId();
            if (deviceUserId == null || queued.contains(key(entry.getKey(), deviceUserId))) {
                continue;
            }
            Device device = devices.findById(entry.getKey()).orElse(null);
            if (device == null) {
                continue;
            }
            DeviceObservedUser observed = observedUsers
                    .findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                    .orElse(null);
            if (observed == null) {
                continue;
            }
            DesiredMemberProjection existing = projections
                    .findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                    .orElse(null);
            if (existing != null && existing.getMemberId() != null) {
                continue;
            }
            desired.publishAbsence(device, observed);
            remember(queued, device.getId(), deviceUserId);
        }
    }

    private static void remember(Set<String> queued, Long deviceId, String deviceUserId) {
        if (deviceUserId != null && !deviceUserId.isBlank()) {
            queued.add(key(deviceId, deviceUserId));
        }
    }

    private static String key(Long deviceId, String deviceUserId) {
        return deviceId + ":" + deviceUserId;
    }
}
