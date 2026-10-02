package com.example.gym.device;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.EnrollmentStatus;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.DeviceSyncState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps every member on every gateway-assigned device ({@code deviceUserId = serialNumber}, or the
 * member code for older members without a serial) and fans
 * member, authorization and face changes out as outbox commands. The server is the hub: a change
 * from React or from one device is stored first, then pushed to every device except the ones that
 * already hold it ({@code skipDeviceIds}).
 */
@Service
public class MemberDeviceProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(MemberDeviceProvisioningService.class);
    private static final List<SyncCommandType> FACE_TYPES =
            List.of(SyncCommandType.UPSERT_FACE, SyncCommandType.DELETE_FACE);

    private final DeviceRepository deviceRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final MemberRepository memberRepository;
    private final DeviceAuthorizationService authorizationService;
    private final MemberFaceRepository faceRepository;
    private final DeviceSyncService deviceSyncService;
    private final AuditService auditService;

    public MemberDeviceProvisioningService(DeviceRepository deviceRepository,
                                           MemberDeviceMappingRepository mappingRepository,
                                           MemberRepository memberRepository,
                                           DeviceAuthorizationService authorizationService,
                                           MemberFaceRepository faceRepository,
                                           DeviceSyncService deviceSyncService,
                                           AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.mappingRepository = mappingRepository;
        this.memberRepository = memberRepository;
        this.authorizationService = authorizationService;
        this.faceRepository = faceRepository;
        this.deviceSyncService = deviceSyncService;
        this.auditService = auditService;
    }

    /** Ensures a mapping on every gateway-assigned device of the tenant; seeds new mappings. */
    @Transactional
    public int provisionMember(Member member, Set<Long> skipDeviceIds) {
        int added = 0;
        for (Device device : deviceRepository.findByTenantId(member.getTenantId())) {
            if (device.getGatewayId() == null || skipDeviceIds.contains(device.getId())) {
                continue;
            }
            if (ensureMapping(member, device)) {
                added++;
            }
        }
        return added;
    }

    /** Backfills all active members onto a device that just got a gateway. */
    @Transactional
    public int provisionDevice(Device device) {
        if (device.getGatewayId() == null) {
            return 0;
        }
        int added = 0;
        for (Member member : memberRepository.findByTenantIdAndStatus(device.getTenantId(), MemberStatus.ACTIVE)) {
            if (ensureMapping(member, device)) {
                added++;
            }
        }
        if (added > 0) {
            log.info("Backfilled {} members onto device {}", added, device.getPublicId());
        }
        return added;
    }

    /**
     * Records that {@code device} already holds this member (and optionally a face version) because
     * the change originated there. No commands are sent to that device.
     */
    @Transactional
    public MemberDeviceMapping markHeldBySource(Member member, Device device, String deviceUserId,
                                                Integer faceVersion) {
        MemberDeviceMapping mapping = mappingRepository
                .findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElseGet(() -> new MemberDeviceMapping(member.getTenantId(), member.getId(),
                        device.getId(), deviceUserId));
        mapping.setEnrollmentStatus(EnrollmentStatus.ENROLLED);
        mapping.setSyncState(DeviceSyncState.SYNCED);
        if (mapping.getEnrolledAt() == null) {
            mapping.setEnrolledAt(Instant.now());
        }
        if (faceVersion != null) {
            deviceSyncService.supersede(device.getId(), member.getId(), FACE_TYPES);
            mapping.setFaceVersionSynced(faceVersion);
            mapping.setFaceSyncState(DeviceSyncState.SYNCED);
            mapping.setFaceLastError(null);
        }
        return mappingRepository.save(mapping);
    }

    /** Pushes the member's current face to every mapped device not in {@code skipDeviceIds}. */
    @Transactional
    public void pushFace(Member member, MemberFace face, Set<Long> skipDeviceIds) {
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            if (!skipDeviceIds.contains(mapping.getDeviceId())) {
                enqueueFace(member, mapping, face);
            }
        }
    }

    /** Re-pushes the server's face to one device (e.g. it reported an older change). */
    @Transactional
    public void repushFace(Member member, Long deviceId) {
        MemberFace face = faceRepository.findByMemberId(member.getId()).orElse(null);
        MemberDeviceMapping mapping = mappingRepository.findByDeviceIdAndMemberId(deviceId, member.getId())
                .orElse(null);
        if (mapping == null) {
            return;
        }
        if (face == null) {
            enqueueFaceDelete(member, mapping);
        } else {
            enqueueFace(member, mapping, face);
        }
    }

    @Transactional
    public void deleteFace(Member member) {
        deleteFace(member, Set.of());
    }

    /** Removes the face from every mapped device except {@code skipDeviceIds} (already without it). */
    @Transactional
    public void deleteFace(Member member, Set<Long> skipDeviceIds) {
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            if (skipDeviceIds.contains(mapping.getDeviceId())) {
                deviceSyncService.supersede(mapping.getDeviceId(), member.getId(), FACE_TYPES);
                mapping.setFaceVersionSynced(null);
                mapping.setFaceSyncState(DeviceSyncState.NOT_SYNCED);
                mapping.setFaceLastError(null);
                mappingRepository.save(mapping);
            } else {
                enqueueFaceDelete(member, mapping);
            }
        }
    }

    /**
     * Sends the member's full current state (details, access, face or face removal) to every
     * gateway device, creating missing mappings. Used when the server's version must replace what
     * devices hold (e.g. an older deletion, or a device user that turned out to be someone else);
     * the caller bumps the member's change times first so gateways accept it over their own.
     */
    @Transactional
    public void reseedEverywhere(Member member) {
        deviceSyncService.cancelOpenForMember(member.getId());
        for (Device device : deviceRepository.findByTenantId(member.getTenantId())) {
            if (device.getGatewayId() == null) {
                continue;
            }
            MemberDeviceMapping mapping = mappingRepository.findByDeviceIdAndMemberId(device.getId(), member.getId())
                    .orElse(null);
            if (mapping == null) {
                if (mappingRepository.existsByDeviceIdAndDeviceUserId(device.getId(), member.getDeviceUserId())) {
                    log.warn("Device {} already uses user id {} for another member; not re-creating {}",
                            device.getPublicId(), member.getDeviceUserId(), member.getPublicId());
                    continue;
                }
                mapping = new MemberDeviceMapping(member.getTenantId(), member.getId(), device.getId(),
                        member.getDeviceUserId());
            }
            mapping.setSyncState(DeviceSyncState.PENDING);
            mapping = mappingRepository.save(mapping);
            seed(member, device, mapping);
            if (faceRepository.findByMemberId(member.getId()).isEmpty()) {
                enqueueFaceDelete(member, mapping);
            }
        }
    }

    /** Removes a device user that belongs to a deleted member (e.g. a stale edit re-reported it). */
    @Transactional
    public void removeFrom(Member member, Long deviceId, String deviceUserId) {
        deviceSyncService.enqueue(member.getTenantId(), deviceId, member.getId(), null,
                SyncCommandType.REMOVE_USER, Map.of("deviceUserId", deviceUserId));
    }

    /**
     * Removes the member from every device and drops the mappings. REMOVE_USER is also sent to
     * {@code sourceDeviceId} (idempotent) in case a queued repair re-created the user there.
     */
    @Transactional
    public void removeEverywhere(Member member) {
        deviceSyncService.cancelOpenForMember(member.getId());
        List<MemberDeviceMapping> mappings = mappingRepository.findByMemberId(member.getId());
        for (MemberDeviceMapping mapping : mappings) {
            deviceSyncService.enqueue(member.getTenantId(), mapping.getDeviceId(), member.getId(), null,
                    SyncCommandType.REMOVE_USER, Map.of("deviceUserId", mapping.getDeviceUserId()));
            mappingRepository.delete(mapping);
        }
        if (!mappings.isEmpty()) {
            FlowLog.info("device", "removed member={} from {} device(s)", member.getPublicId(), mappings.size());
            auditService.record(AuditActions.DEVICE_MAPPING_REMOVED, AuditActions.RESULT_SUCCESS,
                    "Member", member.getPublicId(), Map.of("devices", mappings.size()));
        }
    }

    /** Pushes name changes to devices (UPDATE_USER), skipping devices that already have them. */
    @Transactional
    public void pushProfile(Member member, Set<Long> skipDeviceIds) {
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            if (skipDeviceIds.contains(mapping.getDeviceId())) {
                continue;
            }
            deviceSyncService.enqueue(member.getTenantId(), mapping.getDeviceId(), member.getId(), null,
                    SyncCommandType.UPDATE_USER, userPayload(member, mapping.getDeviceUserId()));
        }
    }

    /**
     * Re-pushes the server's name and authorization to one device. Used when a device reports an
     * older name, or a validity / frozen edit (those stay server-authoritative).
     */
    @Transactional
    public void repushUser(Member member, Long deviceId) {
        MemberDeviceMapping mapping = mappingRepository.findByDeviceIdAndMemberId(deviceId, member.getId())
                .orElse(null);
        if (mapping == null) {
            return;
        }
        deviceSyncService.enqueue(member.getTenantId(), deviceId, member.getId(), null,
                SyncCommandType.UPDATE_USER, userPayload(member, mapping.getDeviceUserId()));
        enqueueAuthorization(member, deviceId, mapping.getDeviceUserId());
    }

    /**
     * The member's serial changed. Each reader that holds the member under another id gets the same
     * person created under the serial; the old id stays mapped (and keeps opening the door) until
     * the reader confirms the create, then {@link #completeMove} removes it. Readers already on the
     * serial are not rewritten. The member row is never replaced.
     */
    @Transactional
    public void moveToSerial(Member member) {
        String serial = member.getSerialNumber();
        if (serial == null) {
            return;
        }
        for (MemberDeviceMapping mapping : mappingRepository.findByMemberId(member.getId())) {
            if (serial.equals(mapping.getDeviceUserId())) {
                if (mapping.getPendingDeviceUserId() != null) {
                    deviceSyncService.supersede(mapping.getDeviceId(), member.getId(),
                            List.of(SyncCommandType.CREATE_USER));
                    mapping.setPendingDeviceUserId(null);
                    mappingRepository.save(mapping);
                }
                continue;
            }
            mapping.setPendingDeviceUserId(serial);
            mapping.setSyncState(DeviceSyncState.PENDING);
            mappingRepository.save(mapping);
            deviceSyncService.enqueue(member.getTenantId(), mapping.getDeviceId(), member.getId(), null,
                    SyncCommandType.CREATE_USER, userPayload(member, serial));
        }
    }

    /**
     * A reader created the member under the serial it was moving to: the mapping switches to it, the
     * old id is removed from that reader, and access and photo follow under the new id. The removal
     * goes first because it replaces every earlier open write for the member on that reader, and a
     * reader may refuse the same face on two users.
     */
    @EventListener
    @Transactional
    public void completeMove(DeviceUserCreated created) {
        MemberDeviceMapping mapping = mappingRepository
                .findByDeviceIdAndMemberId(created.deviceId(), created.memberId()).orElse(null);
        if (mapping == null || !created.deviceUserId().equals(mapping.getPendingDeviceUserId())) {
            return;
        }
        Member member = memberRepository.findById(created.memberId()).orElse(null);
        if (member == null) {
            return;
        }
        if (mappingRepository.findByDeviceIdAndDeviceUserId(created.deviceId(), created.deviceUserId())
                .filter(other -> !other.getId().equals(mapping.getId())).isPresent()) {
            log.warn("Device {} already maps user id {} to another member; member {} keeps id {}",
                    created.deviceId(), created.deviceUserId(), member.getPublicId(), mapping.getDeviceUserId());
            return;
        }
        String previous = mapping.getDeviceUserId();
        mapping.setDeviceUserId(created.deviceUserId());
        mapping.setPendingDeviceUserId(null);
        MemberDeviceMapping saved = mappingRepository.save(mapping);

        deviceSyncService.enqueue(member.getTenantId(), saved.getDeviceId(), member.getId(), null,
                SyncCommandType.REMOVE_USER, Map.of("deviceUserId", previous));
        enqueueAuthorization(member, saved.getDeviceId(), saved.getDeviceUserId());
        faceRepository.findByMemberId(member.getId()).ifPresent(face -> enqueueFace(member, saved, face));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deviceId", saved.getDeviceId());
        details.put("serialNumber", member.getSerialNumber());
        details.put("deviceUserId", saved.getDeviceUserId());
        details.put("previousDeviceUserId", previous);
        auditService.recordSystem(AuditActions.MEMBER_DEVICE_USER_MOVED, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), member.getTenantId(), "gateway", details);
        log.info("Member {} moved from device user {} to {} on device {}", member.getPublicId(), previous,
                saved.getDeviceUserId(), saved.getDeviceId());
    }

    /** Asks the gateway to send this device user's current profile + face (DEVICE_USER_CHANGED). */
    @Transactional
    public void requestDeviceReport(Member member, Device device, String deviceUserId) {
        deviceSyncService.enqueue(member.getTenantId(), device.getId(), member.getId(), null,
                SyncCommandType.REPORT_DEVICE_USER, Map.of("deviceUserId", deviceUserId));
    }

    // --- internals -------------------------------------------------------------------------------

    private boolean ensureMapping(Member member, Device device) {
        if (mappingRepository.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            return false;
        }
        String deviceUserId = member.getDeviceUserId();
        if (mappingRepository.existsByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)) {
            log.warn("Device {} already uses user id {} for another member; skipping auto-map of {}",
                    device.getPublicId(), deviceUserId, member.getPublicId());
            return false;
        }
        MemberDeviceMapping mapping = mappingRepository.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), device.getId(), deviceUserId));
        seed(member, device, mapping);
        return true;
    }

    private void seed(Member member, Device device, MemberDeviceMapping mapping) {
        deviceSyncService.enqueue(member.getTenantId(), device.getId(), member.getId(), null,
                SyncCommandType.CREATE_USER, userPayload(member, mapping.getDeviceUserId()));
        enqueueAuthorization(member, device.getId(), mapping.getDeviceUserId());
        faceRepository.findByMemberId(member.getId()).ifPresent(face -> enqueueFace(member, mapping, face));
    }

    /** Sends the member's current access window to one device. */
    @Transactional
    public void pushAccess(Member member, Long deviceId, String deviceUserId) {
        enqueueAuthorization(member, deviceId, deviceUserId);
    }

    private void enqueueAuthorization(Member member, Long deviceId, String deviceUserId) {
        authorizationService.enqueueFor(member, deviceId, deviceUserId);
    }

    private void enqueueFace(Member member, MemberDeviceMapping mapping, MemberFace face) {
        deviceSyncService.supersede(mapping.getDeviceId(), member.getId(), FACE_TYPES);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceUserId", mapping.getDeviceUserId());
        payload.put("memberId", member.getPublicId());
        payload.put("faceVersion", face.getFaceVersion());
        payload.put("sha256", face.getSha256());
        deviceSyncService.enqueue(member.getTenantId(), mapping.getDeviceId(), member.getId(), null,
                SyncCommandType.UPSERT_FACE, payload);
    }

    private void enqueueFaceDelete(Member member, MemberDeviceMapping mapping) {
        deviceSyncService.supersede(mapping.getDeviceId(), member.getId(), FACE_TYPES);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceUserId", mapping.getDeviceUserId());
        deviceSyncService.enqueue(member.getTenantId(), mapping.getDeviceId(), member.getId(), null,
                SyncCommandType.DELETE_FACE, payload);
    }

    static Map<String, Object> userPayload(Member member, String deviceUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceUserId", deviceUserId);
        payload.put("memberCode", member.getMemberCode());
        payload.put("name", member.getFullName());
        payload.put("authority", member.getDeviceAuthority() != null ? member.getDeviceAuthority().name() : "USER");
        return payload;
    }
}
