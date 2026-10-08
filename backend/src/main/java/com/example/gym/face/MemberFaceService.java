package com.example.gym.face;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.MemberDeviceProvisioningService;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberService;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member face photos: validate + normalise, and store a new version on the faces volume. A reader
 * on desired-state sync gets a face revision. Other readers still get a photo command. Also serves
 * images to staff and to the gateway, and accepts images the gateway read from a device.
 */
@Service
public class MemberFaceService {

    private final MemberFaceRepository faceRepository;
    private final GatewayFaceUploadRepository uploadRepository;
    private final FaceStorageService storage;
    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final MemberDeviceProvisioningService provisioning;
    private final AuditService auditService;
    private final DeviceRepository deviceRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final DesiredProjectionService desiredProjection;

    public MemberFaceService(MemberFaceRepository faceRepository,
                             GatewayFaceUploadRepository uploadRepository,
                             FaceStorageService storage,
                             MemberService memberService,
                             MemberRepository memberRepository,
                             MemberDeviceProvisioningService provisioning,
                             AuditService auditService,
                             DeviceRepository deviceRepository,
                             MemberDeviceMappingRepository mappingRepository,
                             DesiredProjectionService desiredProjection) {
        this.faceRepository = faceRepository;
        this.uploadRepository = uploadRepository;
        this.storage = storage;
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.provisioning = provisioning;
        this.auditService = auditService;
        this.deviceRepository = deviceRepository;
        this.mappingRepository = mappingRepository;
        this.desiredProjection = desiredProjection;
    }

    /** Staff upload (React). Returns the current face (unchanged when the image is identical). */
    @Transactional
    public MemberFace upload(String memberPublicId, byte[] raw, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        Optional<MemberFace> existing = faceRepository.findByMemberId(member.getId());
        String previousSha = existing.map(MemberFace::getSha256).orElse(null);
        int previousVersion = existing.map(MemberFace::getFaceVersion).orElse(-1);
        MemberFace face = storeUploaded(member, raw);
        boolean unchanged = previousSha != null && previousSha.equals(face.getSha256()) && previousVersion == face.getFaceVersion();
        if (!unchanged) {
            provisioning.pushFace(member, face, Set.of());
            desiredProjection.publishFace(member);
        }
        return face;
    }

    /**
     * Stores a staff photo without pushing it. Used when the caller writes a desired projection in
     * the same transaction, before any device mapping exists.
     */
    @Transactional
    public MemberFace storeUploaded(Member member, byte[] raw) {
        byte[] jpeg = FaceImageProcessor.normalise(raw, FaceImageProcessor.MIN_SIDE_MANUAL);
        String sha = FaceStorageService.sha256(jpeg);
        Optional<MemberFace> existing = faceRepository.findByMemberId(member.getId());
        if (existing.isPresent() && sha.equals(existing.get().getSha256())) {
            return existing.get();
        }
        MemberFace face = store(member, jpeg, sha, FaceSource.MANUAL, null, Instant.now());
        member.setFaceChangedAt(face.getChangedAt());
        memberRepository.save(member);
        FlowLog.info("face", "photo stored member={} version={}", member.getPublicId(), face.getFaceVersion());
        auditService.record(AuditActions.MEMBER_FACE_UPDATED, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), serialDetails(member, "faceVersion", face.getFaceVersion()));
        return face;
    }

    /**
     * Applies a face that came from a device (already stored as a gateway upload). The caller has
     * decided this change wins; the source device is not re-sent the image.
     */
    @Transactional
    public MemberFace applyFromDevice(Member member, GatewayFaceUpload upload, Long sourceDeviceId,
                                      Instant changedAt) {
        byte[] jpeg = storage.read(upload.getObjectKey());
        MemberFace face = store(member, jpeg, upload.getSha256(), FaceSource.DEVICE, sourceDeviceId, changedAt);
        member.setFaceChangedAt(changedAt);
        memberRepository.save(member);
        upload.markConsumed();
        uploadRepository.save(upload);
        return face;
    }

    @Transactional(readOnly = true)
    public Optional<MemberFace> find(Long memberId) {
        return faceRepository.findByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public Optional<FaceImage> image(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        return faceRepository.findByMemberId(member.getId())
                .filter(f -> storage.exists(f.getObjectKey()))
                .map(f -> new FaceImage(storage.read(f.getObjectKey()), f.getSha256(), f.getFaceVersion()));
    }

    @Transactional
    public void delete(String memberPublicId, Long tenantId) {
        Member member = memberService.getByPublicId(memberPublicId, tenantId);
        MemberFace face = faceRepository.findByMemberId(member.getId()).orElse(null);
        if (face == null) {
            return;
        }
        faceRepository.delete(face);
        storage.deleteAfterCommit(face.getObjectKey());
        member.setFaceChangedAt(Instant.now());
        memberRepository.save(member);
        provisioning.deleteFace(member);
        FlowLog.info("face", "photo removed member={}", member.getPublicId());
        auditService.record(AuditActions.MEMBER_FACE_DELETED, AuditActions.RESULT_SUCCESS,
                "Member", member.getPublicId(), serialDetails(member, null, null));
    }

    private static Map<String, Object> serialDetails(Member member, String key, Object value) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("serialNumber", member.getSerialNumber());
        if (key != null) {
            details.put(key, value);
        }
        return details;
    }

    /** A face was removed on a device and that change wins: drop it here and on the other devices. */
    @Transactional
    public void removeFromDevice(Member member, Long sourceDeviceId, Instant changedAt) {
        MemberFace face = faceRepository.findByMemberId(member.getId()).orElse(null);
        if (face == null) {
            return;
        }
        faceRepository.delete(face);
        storage.deleteAfterCommit(face.getObjectKey());
        member.setFaceChangedAt(changedAt);
        memberRepository.save(member);
        provisioning.deleteFace(member, Set.of(sourceDeviceId));
    }

    /**
     * Gateway download of a specific face version (must be the current one). The member must be
     * mapped to a reader on this gym's gateway. Another gym's gateway is refused.
     */
    @Transactional(readOnly = true)
    public FaceImage imageForGateway(Gateway gateway, String memberPublicId, int version) {
        Member member = memberRepository.findByPublicId(memberPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Member"));
        TenantGuard.check(member.getTenantId(), gateway.getTenantId(), "Member");
        if (!memberAssignedToGateway(gateway, member.getId())) {
            throw CommonExceptions.forbidden("Member is not assigned to a device on this gateway");
        }
        MemberFace face = faceRepository.findByMemberId(member.getId())
                .filter(f -> f.getFaceVersion() == version)
                .orElseThrow(() -> CommonExceptions.notFound("Face version"));
        return new FaceImage(storage.read(face.getObjectKey()), face.getSha256(), face.getFaceVersion());
    }

    /**
     * Stores an image the gateway read from one of its own devices. Returns the upload id to
     * reference from DEVICE_USER_CHANGED. Does not read or replace a member face. A gateway with
     * no assigned device cannot deposit face bytes. Device JPEGs that already fit are kept
     * byte-identical so echo suppression by sha256 stays stable.
     */
    @Transactional
    public GatewayFaceUpload acceptGatewayUpload(Gateway gateway, byte[] raw) {
        if (deviceRepository.findByGatewayId(gateway.getId()).isEmpty()) {
            throw CommonExceptions.forbidden("Gateway has no assigned device");
        }
        byte[] jpeg = FaceImageProcessor.normaliseFromDevice(raw);
        String sha = FaceStorageService.sha256(jpeg);
        String key = FaceStorageService.uploadKey(gateway.getTenantId());
        storage.writeInTransaction(key, jpeg);
        return uploadRepository.save(new GatewayFaceUpload(gateway.getTenantId(), gateway.getId(), key, sha,
                jpeg.length));
    }

    private boolean memberAssignedToGateway(Gateway gateway, Long memberId) {
        for (Device device : deviceRepository.findByGatewayId(gateway.getId())) {
            if (mappingRepository.existsByDeviceIdAndMemberId(device.getId(), memberId)) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<GatewayFaceUpload> findUpload(String publicId, Long tenantId) {
        return uploadRepository.findByPublicId(publicId)
                .filter(u -> u.getTenantId().equals(tenantId));
    }

    /** Drops gateway uploads that were never attached to a member (or already copied). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void purgeOldUploads() {
        for (GatewayFaceUpload upload : uploadRepository.findByCreatedAtBefore(
                Instant.now().minus(1, ChronoUnit.DAYS))) {
            storage.deleteAfterCommit(upload.getObjectKey());
            uploadRepository.delete(upload);
        }
    }

    private MemberFace store(Member member, byte[] jpeg, String sha, FaceSource source, Long sourceDeviceId,
                             Instant changedAt) {
        MemberFace face = faceRepository.findByMemberId(member.getId())
                .orElseGet(() -> new MemberFace(member.getTenantId(), member.getId()));
        String previousKey = face.getObjectKey();
        int nextVersion = face.getFaceVersion() + 1;
        String key = FaceStorageService.memberKey(member.getTenantId(), member.getId(), nextVersion);
        storage.writeInTransaction(key, jpeg);
        face.replace(key, sha, jpeg.length, source, sourceDeviceId, changedAt);
        MemberFace saved = faceRepository.save(face);
        if (previousKey != null && !previousKey.equals(key)) {
            storage.deleteAfterCommit(previousKey);
        }
        return saved;
    }

    public record FaceImage(byte[] bytes, String sha256, int version) {
    }
}
