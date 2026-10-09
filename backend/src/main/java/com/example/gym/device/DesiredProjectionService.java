package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReaderBlockedUser;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.dto.DesiredStateRequests.AcknowledgeRevision;
import com.example.gym.device.dto.DesiredStateRequests.DesiredItem;
import com.example.gym.device.dto.DesiredStateRequests.DesiredPage;
import com.example.gym.device.dto.DesiredStateRequests.ReportOccupied;
import com.example.gym.device.dto.DesiredStateRequests.RevisionNotice;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderBlockedUserRepository;
import com.example.gym.device.repo.ReaderRevisionRepository;
import com.example.gym.face.FaceStorageService;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Desired member for one flagged reader. The gateway is told the reader id and the revision, then
 * pulls the record. An acknowledgement is accepted only when the read-back matches. This path does
 * not compare timestamps and does not choose a replacement id.
 */
@Service
public class DesiredProjectionService {

    /** The measured reader clock is India local. Dates are not converted to UTC before the write. */
    static final ZoneId READER_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter READER_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    static final String AUTHORITY = "Customer";
    static final int USER_STATUS = 0;
    static final int DOOR_NUM = 1;
    static final int TIME_SECTION_NUM = 1;
    private static final int NAME_LIMIT = 31;
    private static final int NAME_EX_LIMIT = 127;
    private static final int PAGE_LIMIT = 50;

    private final DeviceRepository devices;
    private final GatewayRepository gateways;
    private final MemberDeviceMappingRepository mappings;
    private final ReaderRevisionRepository revisions;
    private final DesiredMemberProjectionRepository projections;
    private final ReaderBlockedUserRepository blocked;
    private final MemberFaceRepository faces;
    private final FaceStorageService storage;
    private final DeviceUserIdAllocator allocator;
    private final DeviceAuthorizationService authorization;
    private final GatewaySessionRegistry sessions;
    private final ReaderReviewService reviews;
    private final AttendanceLinker attendance;

    public DesiredProjectionService(DeviceRepository devices,
                                    GatewayRepository gateways,
                                    MemberDeviceMappingRepository mappings,
                                    ReaderRevisionRepository revisions,
                                    DesiredMemberProjectionRepository projections,
                                    ReaderBlockedUserRepository blocked,
                                    MemberFaceRepository faces,
                                    FaceStorageService storage,
                                    DeviceUserIdAllocator allocator,
                                    DeviceAuthorizationService authorization,
                                    GatewaySessionRegistry sessions,
                                    ReaderReviewService reviews,
                                    AttendanceLinker attendance) {
        this.devices = devices;
        this.gateways = gateways;
        this.mappings = mappings;
        this.revisions = revisions;
        this.projections = projections;
        this.blocked = blocked;
        this.faces = faces;
        this.storage = storage;
        this.allocator = allocator;
        this.authorization = authorization;
        this.sessions = sessions;
        this.reviews = reviews;
        this.attendance = attendance;
    }

    /**
     * An empty flagged reader receives this server member through the existing desired-state writer.
     * The id is allocated for this reader. Nothing is copied from another reader.
     *
     * @return the device user id written, or null when this reader already has the member or has no face
     */
    @Transactional
    public String seedIfAbsent(Member member, Device device) {
        if (device.getGatewayId() == null) {
            return null;
        }
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            return null;
        }
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        if (face == null) {
            return null;
        }
        write(member, device, face);
        return mappings.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .map(MemberDeviceMapping::getDeviceUserId)
                .orElse(null);
    }

    @Transactional
    public long write(Member member, Device device, MemberFace face) {
        if (device.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            throw CommonExceptions.conflict("Member is already mapped to this reader");
        }
        String deviceUserId = allocator.allocate(device.getId());
        MemberDeviceMapping saved = mappings.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), device.getId(), deviceUserId));
        attendance.linkEarlierEvents(saved);

        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseGet(() -> revisions.save(new ReaderRevision(member.getTenantId(), device.getId())));
        long revision = cursor.bumpDesired();

        DesiredMemberProjection row = new DesiredMemberProjection(
                member.getTenantId(), device.getId(), member.getId());
        fill(row, revision, deviceUserId, member, face);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired member reader={} revision={} user={}",
                device.getPublicId(), revision, deviceUserId);
        return revision;
    }

    /**
     * Staff change the name, the membership dates, or access, for readers that already have this
     * member. The revision is the whole user: {@code szName}, {@code szNameEx}, status, and
     * reader-local validity. The end of the local day is 23:59:59. The mapping and {@code publicId}
     * stay. A partial name or date command is not the writer for a flagged reader.
     */
    @Transactional
    public void publishAccess(Member member) {
        int status = readerStatus(member);
        String fullName = member.getFullName();
        String name = szName(fullName);
        String nameEx = szNameEx(fullName);
        LocalDate today = LocalDate.now(READER_ZONE);
        var window = authorization.window(member, today);
        for (var mapping : mappings.findByMemberId(member.getId())) {
            Device device = devices.findById(mapping.getDeviceId()).orElse(null);
            if (device == null || device.getGatewayId() == null) {
                continue;
            }
            DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                    .orElse(null);
            if (row == null) {
                continue;
            }
            String from = window.map(access -> at(access.validFrom(), LocalTime.MIN)).orElse(row.getValidFrom());
            String to = window.map(access -> at(access.validTo(), LocalTime.of(23, 59, 59))).orElse(row.getValidTo());
            if (row.getUserStatus() == status
                    && from.equals(row.getValidFrom())
                    && to.equals(row.getValidTo())
                    && name.equals(row.getReaderName())
                    && sameNameEx(nameEx, row.getReaderNameEx())) {
                continue;
            }
            ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                    .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
            long revision = cursor.bumpDesired();
            row.setRevision(revision);
            row.setReaderName(name);
            row.setReaderNameEx(nameEx);
            row.setUserStatus(status);
            row.setValidFrom(from);
            row.setValidTo(to);
            row.setDeviceUserId(mapping.getDeviceUserId());
            projections.save(row);
            notifyAfterCommit(device, revision);
            FlowLog.info("device", "desired member reader={} revision={} user={} status={}",
                    device.getPublicId(), revision, mapping.getDeviceUserId(), status);
        }
    }

    /**
     * Staff replace the photo for readers that already have this member. The revision keeps the
     * same device user id and the current user record, and points at the new face. The read-back
     * hash is recorded only when that revision is acknowledged.
     */
    @Transactional
    public void publishFace(Member member) {
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        if (face == null || face.getSha256() == null || face.getSha256().isBlank()) {
            return;
        }
        for (var mapping : mappings.findByMemberId(member.getId())) {
            Device device = devices.findById(mapping.getDeviceId()).orElse(null);
            if (device == null || device.getGatewayId() == null) {
                continue;
            }
            DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                    .orElse(null);
            if (row == null || !row.isPresentOnReader()) {
                continue;
            }
            boolean sameFace = face.getSha256().equalsIgnoreCase(row.getFaceSha256()) && row.isFacePresent();
            if (sameFace) {
                continue;
            }
            ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                    .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
            long revision = cursor.bumpDesired();
            row.setRevision(revision);
            row.setFacePresent(true);
            row.setFaceSha256(face.getSha256());
            row.setObservedFaceSha256(null);
            row.setDeviceUserId(mapping.getDeviceUserId());
            projections.save(row);
            notifyAfterCommit(device, revision);
            FlowLog.info("device", "desired face reader={} revision={} user={}",
                    device.getPublicId(), revision, mapping.getDeviceUserId());
        }
    }

    /**
     * Staff deleted the server photo. Each reader that already has this member keeps the user and
     * drops the face. A reader that does not have the member is left alone, and the member stays.
     */
    @Transactional
    public void publishFaceCleared(Member member) {
        for (var mapping : mappings.findByMemberId(member.getId())) {
            Device device = devices.findById(mapping.getDeviceId()).orElse(null);
            if (device == null || device.getGatewayId() == null) {
                continue;
            }
            DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                    .orElse(null);
            if (row == null || !row.isPresentOnReader() || !row.isFacePresent()) {
                continue;
            }
            ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                    .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
            long revision = cursor.bumpDesired();
            row.setRevision(revision);
            row.setFacePresent(false);
            row.setObservedFaceSha256(null);
            projections.save(row);
            notifyAfterCommit(device, revision);
            FlowLog.info("device", "desired face cleared reader={} revision={} user={}",
                    device.getPublicId(), revision, mapping.getDeviceUserId());
        }
    }

    /**
     * Staff accepted the server record, or asked to restore it. This is the freeze/enable writer:
     * one new revision of the current member, on this reader only. The device user id stays.
     */
    @Transactional
    public long republish(Member member, Device device) {
        DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Member is not on this reader"));
        MemberDeviceMapping mapping = mappings.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Member mapping is missing"));
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        applyServer(row, member, mapping.getDeviceUserId(), face);
        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired member reader={} revision={} user={}",
                device.getPublicId(), revision, mapping.getDeviceUserId());
        return revision;
    }

    /**
     * Link or create keeps the id the reader already assigned. The next integer is not used.
     */
    @Transactional
    public long writeKeepingId(Member member, Device device, MemberFace face, String deviceUserId,
                               boolean keepDeviceUserId) {
        if (device.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        if (deviceUserId == null || deviceUserId.isBlank()) {
            throw CommonExceptions.badRequest("Device user id is required");
        }
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            throw CommonExceptions.conflict("Member is already mapped to this reader");
        }
        if (mappings.existsByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)) {
            throw CommonExceptions.conflict("Device user id is already mapped");
        }
        MemberDeviceMapping saved = mappings.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), device.getId(), deviceUserId));
        attendance.linkEarlierEvents(saved);
        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseGet(() -> revisions.save(new ReaderRevision(member.getTenantId(), device.getId())));
        long revision = cursor.bumpDesired();
        DesiredMemberProjection row = new DesiredMemberProjection(
                member.getTenantId(), device.getId(), member.getId());
        fill(row, revision, deviceUserId, member, face);
        row.setKeepDeviceUserId(keepDeviceUserId);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired member reader={} revision={} user={}",
                device.getPublicId(), revision, deviceUserId);
        return revision;
    }

    /**
     * Reject removes this device user and does not attach a member. The gateway's existing
     * absence apply is the writer.
     */
    @Transactional
    public long publishAbsence(Device device, String deviceUserId, String name,
                               String validFrom, String validTo, String faceSha256) {
        if (deviceUserId == null || deviceUserId.isBlank()) {
            throw CommonExceptions.badRequest("Device user id is required");
        }
        DesiredMemberProjection row = projections.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (row != null && row.getMemberId() != null) {
            throw CommonExceptions.conflict("Device user id belongs to a member");
        }
        if (row == null) {
            row = new DesiredMemberProjection(device.getTenantId(), device.getId(), null);
            row.setDoorNum(DOOR_NUM);
            row.setTimeSectionNum(TIME_SECTION_NUM);
        }
        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseGet(() -> revisions.save(new ReaderRevision(device.getTenantId(), device.getId())));
        long revision = cursor.bumpDesired();
        LocalDate today = LocalDate.now(READER_ZONE);
        row.setRevision(revision);
        row.setDeviceUserId(deviceUserId);
        row.setPresentOnReader(false);
        row.setReaderName(name == null || name.isBlank() ? deviceUserId : szName(name));
        row.setReaderNameEx(null);
        row.setUserStatus(USER_STATUS);
        row.setValidFrom(blank(validFrom) ? at(today, LocalTime.MIN) : cut(validFrom, 40));
        row.setValidTo(blank(validTo) ? at(today, LocalTime.of(23, 59, 59)) : cut(validTo, 40));
        row.setAuthority(AUTHORITY);
        row.setFaceSha256(faceSha256 != null && faceSha256.length() == 64 ? faceSha256 : "0".repeat(64));
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired absence reader={} revision={} user={}",
                device.getPublicId(), revision, deviceUserId);
        return revision;
    }

    /**
     * Staff remove this member from one flagged reader. Presence becomes false. The member stays,
     * and no other reader is written.
     */
    @Transactional
    public long publishRemoval(Member member, Device device) {
        DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElse(null);
        if (row == null || !row.isPresentOnReader()) {
            return 0;
        }
        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        row.setPresentOnReader(false);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired absence reader={} revision={} user={}",
                device.getPublicId(), revision, row.getDeviceUserId());
        return revision;
    }

    /** Inactive is access disallowed, not an archive. A member with no plan stays enabled, as V1 wrote them. */
    private int readerStatus(Member member) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return 1;
        }
        if (authorization.window(member).isEmpty()) {
            return USER_STATUS;
        }
        return authorization.desiredEnabled(member) ? USER_STATUS : 1;
    }

    @Transactional(readOnly = true)
    public DesiredPage pull(Gateway gateway, String devicePublicId, long after, int limit) {
        if (after < 0) {
            throw CommonExceptions.badRequest("after must be zero or greater");
        }
        Device device = ownedReader(gateway, devicePublicId);
        ReaderRevision cursor = revisions.findByDeviceId(device.getId()).orElse(null);
        long desired = cursor == null ? 0 : cursor.getDesiredRevision();
        long applied = cursor == null ? 0 : cursor.getAppliedRevision();
        int page = Math.min(Math.max(limit, 1), PAGE_LIMIT);
        List<DesiredItem> items = projections
                .findByDeviceIdAndRevisionGreaterThanOrderByRevisionAsc(device.getId(), after, PageRequest.of(0, page))
                .stream()
                .map(this::item)
                .toList();
        return new DesiredPage(desired, applied, items);
    }

    @Transactional
    public RevisionNotice acknowledge(Gateway gateway, AcknowledgeRevision ack) {
        Device device = ownedReader(gateway, ack.deviceId());
        DesiredMemberProjection row = projections.findByDeviceIdAndRevision(device.getId(), ack.revision())
                .orElseThrow(() -> CommonExceptions.conflict("Revision is not the desired member"));
        if (!matches(row, ack)) {
            String error = "Read-back does not match the desired member";
            if (ack.failCode() != null && !ack.failCode().isBlank()) {
                error = error + ": " + ack.failCode();
            }
            reviews.noteVerificationFailure(device.getId(), ack.revision(), error);
            throw CommonExceptions.conflict(error);
        }
        if (row.isPresentOnReader() && row.isFacePresent()
                && ack.faceSha256() != null && !ack.faceSha256().isBlank()) {
            row.setObservedFaceSha256(ack.faceSha256());
            projections.save(row);
        }
        if (row.isPresentOnReader()) {
            reviews.reconcile(row);
        }
        reviews.settle(device.getId(), ack.revision());
        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
        if (ack.revision() > cursor.getAppliedRevision()) {
            cursor.setAppliedRevision(ack.revision());
            revisions.save(cursor);
        }
        return new RevisionNotice(device.getPublicId(), cursor.getAppliedRevision());
    }

    /**
     * The reader already has this id. Remember it, allocate the next integer, and publish a new
     * revision. The caller does not choose the replacement id.
     */
    @Transactional
    public RevisionNotice occupied(Gateway gateway, ReportOccupied report) {
        Device device = ownedReader(gateway, report.deviceId());
        DesiredMemberProjection row = projections.findByDeviceIdAndRevision(device.getId(), report.revision())
                .orElseThrow(() -> CommonExceptions.conflict("Revision is not the desired member"));
        if (!report.deviceUserId().equals(row.getDeviceUserId())) {
            throw CommonExceptions.conflict("Occupied id is not the desired id");
        }
        if (!blocked.existsByDeviceIdAndDeviceUserId(device.getId(), report.deviceUserId())) {
            blocked.save(new ReaderBlockedUser(device.getTenantId(), device.getId(), report.deviceUserId()));
        }
        String nextId = allocator.allocate(device.getId());
        MemberDeviceMapping mapping = mappings.findByDeviceIdAndMemberId(device.getId(), row.getMemberId())
                .orElseThrow(() -> CommonExceptions.conflict("Member mapping is missing"));
        mapping.setDeviceUserId(nextId);
        mappings.save(mapping);

        ReaderRevision cursor = revisions.findByDeviceId(device.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Reader has no revision"));
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        row.setDeviceUserId(nextId);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "occupied id {} on reader={}; retry revision={} user={}",
                report.deviceUserId(), device.getPublicId(), revision, nextId);
        return new RevisionNotice(device.getPublicId(), revision);
    }

    private void applyServer(DesiredMemberProjection row, Member member, String deviceUserId, MemberFace face) {
        int status = readerStatus(member);
        String fullName = member.getFullName();
        LocalDate today = LocalDate.now(READER_ZONE);
        var window = authorization.window(member, today);
        String from = window.map(access -> at(access.validFrom(), LocalTime.MIN)).orElse(row.getValidFrom());
        String to = window.map(access -> at(access.validTo(), LocalTime.of(23, 59, 59))).orElse(row.getValidTo());
        row.setPresentOnReader(true);
        row.setDeviceUserId(deviceUserId);
        row.setReaderName(szName(fullName));
        row.setReaderNameEx(szNameEx(fullName));
        row.setUserStatus(status);
        row.setValidFrom(from);
        row.setValidTo(to);
        row.setAuthority(AUTHORITY);
        row.setObservedFaceSha256(null);
        if (face == null) {
            row.setFacePresent(false);
            return;
        }
        row.setFacePresent(true);
        row.setFaceSha256(face.getSha256());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void fill(DesiredMemberProjection row, long revision, String deviceUserId,
                      Member member, MemberFace face) {
        LocalDate today = LocalDate.now(READER_ZONE);
        var window = authorization.window(member, today);
        LocalDate from = window.map(DeviceAuthorizationService.AccessWindow::validFrom).orElse(today);
        LocalDate to = window.map(DeviceAuthorizationService.AccessWindow::validTo).orElse(today);
        String fullName = member.getFullName();
        row.setRevision(revision);
        row.setDeviceUserId(deviceUserId);
        row.setPresentOnReader(true);
        row.setReaderName(szName(fullName));
        row.setReaderNameEx(szNameEx(fullName));
        row.setUserStatus(USER_STATUS);
        row.setValidFrom(at(from, LocalTime.MIN));
        row.setValidTo(at(to, LocalTime.of(23, 59, 59)));
        row.setAuthority(AUTHORITY);
        row.setDoorNum(DOOR_NUM);
        row.setTimeSectionNum(TIME_SECTION_NUM);
        row.setFacePresent(true);
        row.setFaceSha256(face.getSha256());
    }

    private DesiredItem item(DesiredMemberProjection row) {
        boolean showFace = row.isPresentOnReader() && row.isFacePresent();
        return new DesiredItem(
                row.getRevision(),
                row.getDeviceUserId(),
                row.getReaderName(),
                row.getReaderNameEx(),
                row.getUserStatus(),
                row.getValidFrom(),
                row.getValidTo(),
                row.getAuthority(),
                row.getDoorNum(),
                row.getTimeSectionNum(),
                showFace ? row.getFaceSha256() : "",
                showFace ? faceBytes(row) : "",
                row.isPresentOnReader(),
                row.isKeepDeviceUserId(),
                row.isFacePresent());
    }

    private String faceBytes(DesiredMemberProjection row) {
        MemberFace photo = faces.findByMemberId(row.getMemberId())
                .orElseThrow(() -> CommonExceptions.conflict("Desired member has no face"));
        return Base64.getEncoder().encodeToString(storage.read(photo.getObjectKey()));
    }

    private boolean matches(DesiredMemberProjection row, AcknowledgeRevision ack) {
        if (!row.isPresentOnReader()) {
            return Boolean.FALSE.equals(ack.present())
                    && "NO_RECORD".equals(ack.failCode())
                    && ack.deviceUserId().equals(row.getDeviceUserId());
        }
        if (!userMatches(row, ack)) {
            return false;
        }
        return row.isFacePresent() ? faceHashMatches(ack, row.getFaceSha256()) : faceCleared(ack);
    }

    private boolean userMatches(DesiredMemberProjection row, AcknowledgeRevision ack) {
        if (Boolean.FALSE.equals(ack.present())
                || ack.name() == null
                || ack.userStatus() == null
                || ack.validFrom() == null
                || ack.validTo() == null) {
            return false;
        }
        return ack.deviceUserId().equals(row.getDeviceUserId())
                && ack.name().equals(row.getReaderName())
                && sameNameEx(ack.nameEx(), row.getReaderNameEx())
                && ack.userStatus() == row.getUserStatus()
                && ack.validFrom().equals(row.getValidFrom())
                && ack.validTo().equals(row.getValidTo());
    }

    private static boolean faceHashMatches(AcknowledgeRevision ack, String hash) {
        return ack.faceSha256() != null
                && ack.faceSha256().equalsIgnoreCase(hash)
                && (ack.failCode() == null || ack.failCode().isBlank());
    }

    /** A removed photo reads back as UNKNOWN with no bytes. NO_RECORD means the user is gone. */
    private static boolean faceCleared(AcknowledgeRevision ack) {
        return (ack.faceSha256() == null || ack.faceSha256().isBlank())
                && "UNKNOWN".equals(ack.failCode());
    }

    private static boolean sameNameEx(String reported, String desired) {
        String left = reported == null || reported.isBlank() ? null : reported;
        String right = desired == null || desired.isBlank() ? null : desired;
        return Objects.equals(left, right);
    }

    private Device ownedReader(Gateway gateway, String devicePublicId) {
        Device device = devices.findByPublicId(devicePublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Reader"));
        if (!device.getTenantId().equals(gateway.getTenantId())) {
            throw CommonExceptions.notFound("Reader");
        }
        if (device.getGatewayId() == null || !device.getGatewayId().equals(gateway.getId())) {
            throw CommonExceptions.forbidden("Reader is not on this gateway");
        }
        return device;
    }

    private void notifyAfterCommit(Device device, long revision) {
        String devicePublicId = device.getPublicId();
        Long gatewayId = device.getGatewayId();
        if (gatewayId == null) {
            return;
        }
        Runnable send = () -> gateways.findById(gatewayId).ifPresent(gateway -> {
            String body = "{\"type\":\"DESIRED_REVISION\",\"deviceId\":\"" + devicePublicId
                    + "\",\"revision\":" + revision + "}";
            sessions.send(gateway.getPublicId(), body);
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    static String szName(String fullName) {
        String name = fullName == null ? "" : fullName;
        return name.length() <= NAME_LIMIT ? name : name.substring(0, NAME_LIMIT);
    }

    /** Present only when the name does not fit szName. */
    static String szNameEx(String fullName) {
        if (fullName == null || fullName.length() <= NAME_LIMIT) {
            return null;
        }
        return fullName.length() <= NAME_EX_LIMIT ? fullName : fullName.substring(0, NAME_EX_LIMIT);
    }

    private static String at(LocalDate day, LocalTime time) {
        return OffsetDateTime.of(day, time, READER_ZONE.getRules().getOffset(day.atTime(time)))
                .format(READER_TIME);
    }
}
