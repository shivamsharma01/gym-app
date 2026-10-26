package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReviewItem;
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
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderBlockedUserRepository;
import com.example.gym.face.FaceStorageService;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.DeviceAuthority;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.member.MemberStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desired member for one flagged reader. The gateway is told the reader id and the revision, then
 * pulls the record. An acknowledgement is accepted only when the read-back matches. This path does
 * not compare timestamps and does not choose a replacement id.
 */
@Service
public class DesiredProjectionService {

    private static final Logger log = LoggerFactory.getLogger(DesiredProjectionService.class);

    /** The measured reader clock is India local. Dates are not converted to UTC before the write. */
    static final ZoneId READER_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter READER_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    static final String AUTHORITY = "Customer";
    static final String ADMIN = "ADMIN";
    static final int USER_STATUS = 0;
    static final int DOOR_NUM = 1;
    static final int TIME_SECTION_NUM = 1;
    private static final int NAME_LIMIT = 31;
    private static final int NAME_EX_LIMIT = 127;
    private static final int PAGE_LIMIT = 50;

    private final DeviceRepository devices;
    private final GatewayRepository gateways;
    private final MemberDeviceMappingRepository mappings;
    private final ReaderRevisions revisions;
    private final DesiredMemberProjectionRepository projections;
    private final ReaderBlockedUserRepository blocked;
    private final MemberFaceRepository faces;
    private final FaceStorageService storage;
    private final DeviceUserIdAllocator allocator;
    private final DeviceAuthorizationService authorization;
    private final GatewaySessionRegistry sessions;
    private final ReaderReviewService reviews;
    private final AttendanceLinker attendance;
    private final MemberRepository members;
    private final DeviceObservedUserRepository observedUsers;

    public DesiredProjectionService(DeviceRepository devices,
                                    GatewayRepository gateways,
                                    MemberDeviceMappingRepository mappings,
                                    ReaderRevisions revisions,
                                    DesiredMemberProjectionRepository projections,
                                    ReaderBlockedUserRepository blocked,
                                    MemberFaceRepository faces,
                                    FaceStorageService storage,
                                    DeviceUserIdAllocator allocator,
                                    DeviceAuthorizationService authorization,
                                    GatewaySessionRegistry sessions,
                                    ReaderReviewService reviews,
                                    AttendanceLinker attendance,
                                    MemberRepository members,
                                    DeviceObservedUserRepository observedUsers) {
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
        this.members = members;
        this.observedUsers = observedUsers;
    }

    /**
     * A reader with a trusted list receives this server member through the existing desired-state
     * writer, unless it already has a mapping for the member. The id is allocated for this reader.
     * Nothing is copied from another reader. Calling it again for the same member writes nothing.
     *
     * @return the device user id written, or null when this reader already has the member or has no face
     */
    @Transactional
    public String seedIfAbsent(Member member, Device device) {
        if (device.getGatewayId() == null) {
            return null;
        }
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        if (face == null) {
            return null;
        }
        revisions.lock(device.getTenantId(), device.getId());
        if (mappings.countLocked(device.getId(), member.getId()) > 0
                || reviews.suspended(device.getId(), member.getId())) {
            return null;
        }
        if (!revisions.unchangedSince(member)) {
            FlowLog.info("device", "seed deferred reader={} member={}: member changed meanwhile",
                    device.getPublicId(), member.getPublicId());
            return null;
        }
        write(member, device, face, true);
        return mappings.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .map(MemberDeviceMapping::getDeviceUserId)
                .orElse(null);
    }

    /**
     * Takes back a seed of {@code member} on this reader that the gateway was never sent, so the
     * reader is not given them while it may already hold them under another id. A row the reader
     * may have, or one a decision placed, stays. Call with the reader locked.
     *
     * @param seenAt whether the reader has been seen holding someone at an id
     * @return whether a seed was withdrawn
     */
    @Transactional
    public boolean withdrawUnservedSeed(Member member, Device device, Predicate<String> seenAt) {
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .map(revisions::reload)
                .orElse(null);
        if (row == null || !row.unservedSeed(cursor.getServedRevision())
                || reviews.baseline(device.getId(), row.getDeviceUserId()).isPresent()
                || seenAt.test(row.getDeviceUserId())) {
            return false;
        }
        mappings.findByDeviceIdAndMemberId(device.getId(), member.getId()).ifPresent(mappings::delete);
        projections.delete(row);
        projections.flush();
        FlowLog.info("device", "seed withdrawn reader={} member={} user={}: the reader may already hold them",
                device.getPublicId(), member.getPublicId(), row.getDeviceUserId());
        return true;
    }

    /** The id this reader holds the member under. */
    @Transactional(readOnly = true)
    public String mappedId(Device device, Member member) {
        return mappings.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .map(MemberDeviceMapping::getDeviceUserId)
                .orElseThrow(() -> CommonExceptions.conflict("Member mapping is missing"));
    }

    /** Holds this reader's lock until the caller's transaction ends. */
    @Transactional
    public void lockReader(Device device) {
        revisions.lock(device.getTenantId(), device.getId());
    }

    @Transactional
    public long write(Member member, Device device, MemberFace face) {
        return write(member, device, face, false);
    }

    private long write(Member member, Device device, MemberFace face, boolean seed) {
        if (device.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            throw CommonExceptions.conflict("Member is already mapped to this reader");
        }
        ReaderRevision cursor = revisions.lock(member.getTenantId(), device.getId());
        String deviceUserId = allocator.allocate(cursor);
        retireFinishedAbsence(device, deviceUserId);
        MemberDeviceMapping saved = mappings.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), device.getId(), deviceUserId));
        attendance.linkEarlierEvents(saved);
        long revision = cursor.bumpDesired();

        DesiredMemberProjection row = new DesiredMemberProjection(
                member.getTenantId(), device.getId(), member.getId());
        fill(row, revision, deviceUserId, member, face);
        row.setExpected(ExpectedOccupant.ABSENT);
        if (seed) {
            row.setSeedRevision(revision);
        }
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired member reader={} revision={} user={}",
                device.getPublicId(), revision, deviceUserId);
        return revision;
    }

    /**
     * A finished removal leaves a row with no member. The id can be allocated again. Remove that
     * row before the new member row is saved, so one device user id stays one row.
     */
    private void retireFinishedAbsence(Device device, String deviceUserId) {
        List<DesiredMemberProjection> rows = projections.findAllByDeviceIdAndDeviceUserId(
                device.getId(), deviceUserId);
        if (rows.isEmpty()) {
            return;
        }
        int removed = 0;
        for (DesiredMemberProjection row : rows) {
            if (row.getMemberId() == null && row.isApplied() && !row.isPresentOnReader()) {
                projections.delete(row);
                removed++;
            }
        }
        if (removed == 0) {
            return;
        }
        projections.flush();
        FlowLog.info("device", "removed finished removal reader={} user={} before giving that id to a member",
                device.getPublicId(), deviceUserId);
    }

    /**
     * Staff change the name, the membership dates, or access, for readers that already have this
     * member. The revision is the whole user: {@code szName}, {@code szNameEx}, status, and
     * reader-local validity. The end of the local day is 23:59:59. The mapping and {@code publicId}
     * stay. A partial name or date command is not the writer for a flagged reader.
     */
    @Transactional
    public void publishAccess(Member member) {
        publishAccess(member, null);
    }

    /**
     * Publish the member's current access onto every mapped reader, optionally skipping one reader
     * (for example the reader that already received a review decision with an expected occupant).
     */
    @Transactional
    public void publishAccess(Member member, Long exceptDeviceId) {
        int status = readerStatus(member);
        String fullName = member.getFullName();
        String name = szName(fullName);
        String nameEx = szNameEx(fullName);
        String desiredAuthority = authority(member);
        LocalDate today = LocalDate.now(READER_ZONE);
        var window = authorization.window(member, today);
        for (Held held : inReaderOrder(member)) {
            Device device = held.device();
            if (exceptDeviceId != null && exceptDeviceId.equals(device.getId())) {
                continue;
            }
            ReaderRevision cursor = held.cursor();
            DesiredMemberProjection row = held.row();
            MemberDeviceMapping mapping = held.mapping();
            // Keep-reader / import adopt sets adoptedValid*; prefer that over each row's old dates
            // (and over a stale membership window) so other readers get the approved validity.
            String from;
            String to;
            if (member.getAdoptedValidFrom() != null && member.getAdoptedValidTo() != null) {
                from = member.getAdoptedValidFrom();
                to = member.getAdoptedValidTo();
            } else if (window.isPresent()) {
                from = at(window.get().validFrom(), LocalTime.MIN);
                to = at(window.get().validTo(), LocalTime.of(23, 59, 59));
            } else {
                from = row.getValidFrom();
                to = row.getValidTo();
            }
            // Null-safe: empty membership window or a row missing authority/dates must not 500.
            boolean same = row.getUserStatus() == status
                    && Objects.equals(from, row.getValidFrom())
                    && Objects.equals(to, row.getValidTo())
                    && Objects.equals(name, row.getReaderName())
                    && sameNameEx(nameEx, row.getReaderNameEx())
                    && desiredAuthority.equalsIgnoreCase(row.getAuthority() == null ? AUTHORITY : row.getAuthority());
            // A held row is omitted from pull; membership/access must release it or that reader never updates.
            if (same && !row.isHeld()) {
                continue;
            }
            long revision = row.getRevision();
            if (!same) {
                revision = cursor.bumpDesired();
                row.setRevision(revision);
                row.setReaderName(name);
                row.setReaderNameEx(nameEx);
                row.setUserStatus(status);
                row.setValidFrom(from);
                row.setValidTo(to);
                row.setAuthority(desiredAuthority);
                row.setDeviceUserId(mapping.getDeviceUserId());
            }
            row.setHeld(false);
            row.setExpected(null);
            projections.save(row);
            reviews.closeUndecidedConflict(device, member.getId(), fullName);
            notifyAfterCommit(device, revision);
            FlowLog.info("device", "desired member reader={} revision={} user={} status={} authority={}",
                    device.getPublicId(), revision, mapping.getDeviceUserId(), status, desiredAuthority);
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
        for (Held held : inReaderOrder(member)) {
            Device device = held.device();
            ReaderRevision cursor = held.cursor();
            DesiredMemberProjection row = held.row();
            MemberDeviceMapping mapping = held.mapping();
            boolean sameFace = face.getSha256().equalsIgnoreCase(row.getFaceSha256()) && row.isFacePresent();
            if (!row.isPresentOnReader() || sameFace) {
                continue;
            }
            long revision = cursor.bumpDesired();
            row.setRevision(revision);
            row.setFacePresent(true);
            row.setFaceSha256(face.getSha256());
            // Keep last device-observed hash until ACK so photo reviews stay device-vs-device.
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
        for (Held held : inReaderOrder(member)) {
            Device device = held.device();
            ReaderRevision cursor = held.cursor();
            DesiredMemberProjection row = held.row();
            MemberDeviceMapping mapping = held.mapping();
            if (!row.isPresentOnReader() || !row.isFacePresent()) {
                continue;
            }
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
     * One new revision of the current member, on this reader only. The device user id stays, and so
     * does a hold: the reader still has to show what the gateway was told to expect.
     */
    @Transactional
    public long republish(Member member, Device device) {
        return republish(member, device, null);
    }

    /**
     * Staff accepted the server record, or asked to restore it, having seen {@code seen} on the
     * reader. This is the freeze/enable writer. The gateway writes only while the reader still shows
     * what staff saw, and the hold is released.
     */
    @Transactional
    public long republish(Member member, Device device, ExpectedOccupant seen) {
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        DesiredMemberProjection row = revisions.reload(
                projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member is not on this reader")));
        MemberDeviceMapping mapping = revisions.reload(
                mappings.findByDeviceIdAndMemberId(device.getId(), member.getId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member mapping is missing")));
        MemberFace face = faces.findByMemberId(member.getId()).orElse(null);
        applyServer(row, member, mapping.getDeviceUserId(), face);
        // Held rows are omitted from pull; staff edit/retry must release the hold or nothing is sent.
        row.setHeld(false);
        row.setExpected(seen);
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        projections.save(row);
        // Review decisions pass seen and close the item themselves; retry/edit-without-review closes here.
        if (seen == null) {
            reviews.closeUndecidedConflict(device, member.getId(), member.getFullName());
        }
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired member reader={} revision={} user={}",
                device.getPublicId(), revision, mapping.getDeviceUserId());
        return revision;
    }

    /**
     * Staff keep the reader's shown fields as desired on this reader. The hold is released; the
     * gateway should read back a match and write nothing.
     */
    @Transactional
    public long acceptReaderValues(Member member, Device device, DeviceReviewItem item) {
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        DesiredMemberProjection row = revisions.reload(
                projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member is not on this reader")));
        MemberDeviceMapping mapping = revisions.reload(
                mappings.findByDeviceIdAndMemberId(device.getId(), member.getId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member mapping is missing")));
        ExpectedOccupant seen = ExpectedOccupant.of(item);
        row.setPresentOnReader(true);
        row.setDeviceUserId(mapping.getDeviceUserId());
        if (!blank(item.getReaderName())) {
            row.setReaderName(item.getReaderName());
        }
        row.setReaderNameEx(item.getReaderNameEx());
        if (item.getReaderUserStatus() != null) {
            row.setUserStatus(item.getReaderUserStatus());
        }
        String from = readerTime(item.getReaderValidFrom());
        String to = readerTime(item.getReaderValidTo());
        if (from != null) {
            row.setValidFrom(from);
        }
        if (to != null) {
            row.setValidTo(to);
        }
        if (!blank(item.getReaderAuthority())) {
            row.setAuthority(isAdminAuthority(item.getReaderAuthority()) ? ADMIN : item.getReaderAuthority());
        }
        if (item.getReaderFaceSha256() != null && item.getReaderFaceSha256().length() == 64) {
            row.setFacePresent(true);
            row.setFaceSha256(item.getReaderFaceSha256());
            row.setObservedFaceSha256(item.getReaderFaceSha256());
        }
        row.setExpected(seen);
        row.setHeld(false);
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired keep-reader reader={} revision={} user={}",
                device.getPublicId(), revision, mapping.getDeviceUserId());
        return revision;
    }

    /**
     * Link keeps the id the reader already assigned and writes the member over the reader person
     * staff saw there. The next integer is not used. Someone else at the id is not written over.
     */
    @Transactional
    public long writeKeepingId(Member member, Device device, MemberFace face, DeviceObservedUser observed) {
        String deviceUserId = observed.getDeviceUserId();
        return keepingId(member, device, observed,
                (row, revision) -> fill(row, revision, deviceUserId, member, face));
    }

    /**
     * The other reader already has this member, with the same id the reader chose. Remember that id
     * and copy what the reader shows into the desired row, so the gateway does not write anything
     * until staff later change the member. The member record itself is left as it is.
     */
    @Transactional
    public long recordAlreadyPresent(Member member, Device device, MemberFace face, DeviceObservedUser observed) {
        return keepingId(member, device, observed,
                (row, revision) -> adopt(row, revision, member, face, observed));
    }

    /**
     * A member created from a reader person holds what that reader already shows: name, status,
     * validity, door, time section and photo. The gateway reads it back as matching and writes
     * nothing. The reader is written only when staff later change the member. The caller makes a
     * person the reader shows disabled an inactive member, so the server's own status agrees. The
     * reader's validity is kept on the member, so another reader gets the same dates from the
     * member, not from this reader's row.
     */
    @Transactional
    public long adoptFromReader(Member member, Device device, MemberFace face, DeviceObservedUser observed) {
        long written = keepingId(member, device, observed,
                (row, revision) -> adopt(row, revision, member, face, observed));
        String from = readerTime(observed.getValidFrom());
        String to = readerTime(observed.getValidTo());
        if (from != null && to != null) {
            member.adoptReaderWindow(from, to);
        }
        // Terminal admin on the reader becomes the member's terminal authority so other readers get it.
        if (isAdminAuthority(observed.getAuthority())) {
            member.setDeviceAuthority(DeviceAuthority.ADMIN);
        }
        return written;
    }

    private long keepingId(Member member, Device device, DeviceObservedUser observed,
                           ObjLongConsumer<DesiredMemberProjection> filler) {
        String deviceUserId = observed.getDeviceUserId();
        if (device.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        if (deviceUserId == null || deviceUserId.isBlank()) {
            throw CommonExceptions.badRequest("Device user id is required");
        }
        ReaderRevision cursor = revisions.lock(member.getTenantId(), device.getId());
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            throw CommonExceptions.conflict("Member is already mapped to this reader");
        }
        if (mappings.existsByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)) {
            throw CommonExceptions.conflict("Device user id is already mapped");
        }
        MemberDeviceMapping saved = mappings.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), device.getId(), deviceUserId));
        attendance.linkEarlierEvents(saved);
        long revision = cursor.bumpDesired();
        DesiredMemberProjection row = new DesiredMemberProjection(
                member.getTenantId(), device.getId(), member.getId());
        filler.accept(row, revision);
        row.setExpected(ExpectedOccupant.of(observed));
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired member reader={} revision={} user={}",
                device.getPublicId(), revision, deviceUserId);
        return revision;
    }

    /**
     * The reader still shows a person whose removal was already confirmed. One new removal is
     * published so the gateway deletes them. A removal that is still waiting is left alone.
     */
    @Transactional
    public long republishFinishedAbsence(Device device, DeviceObservedUser observed) {
        String deviceUserId = observed.getDeviceUserId();
        if (deviceUserId == null || deviceUserId.isBlank()) {
            return 0;
        }
        DesiredMemberProjection row = projections.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .orElse(null);
        if (row == null || row.getMemberId() != null || row.isPresentOnReader() || row.isHeld() || !row.isApplied()) {
            return 0;
        }
        return publishAbsence(device, observed);
    }

    /**
     * Reject removes the reader person staff saw at this id and does not attach a member. The
     * gateway's existing absence apply is the writer. Someone else at the id is not removed.
     */
    @Transactional
    public long publishAbsence(Device device, DeviceObservedUser observed) {
        String deviceUserId = observed.getDeviceUserId();
        String name = ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx());
        String validFrom = observed.getValidFrom();
        String validTo = observed.getValidTo();
        String faceSha256 = observed.getFaceSha256();
        if (deviceUserId == null || deviceUserId.isBlank()) {
            throw CommonExceptions.badRequest("Device user id is required");
        }
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        DesiredMemberProjection row = projections.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                .map(revisions::reload)
                .orElse(null);
        if (row != null && row.getMemberId() != null) {
            throw CommonExceptions.conflict("Device user id belongs to a member");
        }
        if (row == null) {
            row = new DesiredMemberProjection(device.getTenantId(), device.getId(), null);
            row.setDoorNum(DOOR_NUM);
            row.setTimeSectionNum(TIME_SECTION_NUM);
        }
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
        row.setExpected(ExpectedOccupant.of(observed));
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
        return publishRemoval(member, device, null);
    }

    /**
     * Staff removed the member having seen {@code seen} on the reader. The gateway removes the id
     * only while the reader still shows it, and the hold is released. A held removal is published again.
     */
    @Transactional
    public long publishRemoval(Member member, Device device, ExpectedOccupant seen) {
        DesiredMemberProjection row = projections.findByDeviceIdAndMemberId(device.getId(), member.getId())
                .orElse(null);
        if (row == null) {
            return 0;
        }
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        revisions.reload(row);
        boolean release = seen != null && row.isHeld();
        if (!row.isPresentOnReader() && !release) {
            return 0;
        }
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        row.setPresentOnReader(false);
        if (seen != null) {
            row.setExpected(seen);
            row.setHeld(false);
        }
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "desired absence reader={} revision={} user={}",
                device.getPublicId(), revision, row.getDeviceUserId());
        return revision;
    }

    /**
     * Inactive is access disallowed, not an archive. A member who never had a membership stays
     * enabled; one whose every membership was cancelled is disabled.
     */
    private int readerStatus(Member member) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return 1;
        }
        if (authorization.window(member).isEmpty()) {
            return USER_STATUS;
        }
        return authorization.desiredEnabled(member) ? USER_STATUS : 1;
    }

    /**
     * One page of rows after {@code after}. The reader is locked and the highest revision served is
     * recorded, so a seed the gateway was never sent can still be withdrawn. Rows are read after the
     * lock, from the latest commits.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DesiredPage pull(Gateway gateway, String devicePublicId, long after, int limit) {
        if (after < 0) {
            throw CommonExceptions.badRequest("after must be zero or greater");
        }
        Device device = ownedReader(gateway, devicePublicId);
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        long desired = cursor.getDesiredRevision();
        long applied = cursor.getAppliedRevision();
        int page = Math.min(Math.max(limit, 1), PAGE_LIMIT);
        List<DesiredItem> items = projections
                .findByDeviceIdAndHeldFalseAndRevisionGreaterThanOrderByRevisionAsc(
                        device.getId(), after, PageRequest.of(0, page))
                .stream()
                .map(this::item)
                .toList();
        if (!items.isEmpty()) {
            cursor.served(items.getLast().revision());
            log.info("{}: handed the gateway members {} to {} of {} to write onto the reader.",
                    device.getName(), items.getFirst().revision(), items.getLast().revision(), desired);
        }
        return new DesiredPage(desired, applied, items);
    }

    @Transactional
    public RevisionNotice acknowledge(Gateway gateway, AcknowledgeRevision ack) {
        Device device = ownedReader(gateway, ack.deviceId());
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        DesiredMemberProjection row = currentAt(device, ack.revision());
        ScanStamp readAfter = ScanStamp.of(ack.scanEpoch(), ack.scanSeq());
        if (readAfter != null && !ObservationOrder.accept(cursor, readAfter)) {
            readAfter = null;
        }
        String mismatch = mismatchReason(row, ack);
        if (mismatch != null) {
            String error = "Read-back does not match the desired member: " + mismatch;
            reviews.noteVerificationFailure(device.getId(), row.getDeviceUserId(), row.getMemberId(),
                    ack.revision(), error);
            throw CommonExceptions.conflict(error);
        }
        if (row.isPresentOnReader() && row.isFacePresent()
                && ack.faceSha256() != null && !ack.faceSha256().isBlank()) {
            row.setObservedFaceSha256(ack.faceSha256());
        }
        row.setExpected(null);
        row.acknowledged(ack.revision());
        projections.save(row);
        if (row.isPresentOnReader()) {
            reviews.reconcile(row, readAfter);
        }
        reviews.settle(device.getId(), row.getDeviceUserId(), row.getMemberId(), ack.revision(), readAfter);
        if (ack.revision() > cursor.getAppliedRevision()) {
            cursor.setAppliedRevision(ack.revision());
        }
        long written = cursor.getAppliedRevision();
        long wanted = cursor.getDesiredRevision();
        if (written == ack.revision() && (written == wanted || written % 25 == 0)) {
            log.info("{}: the reader confirmed member {} ({} of {} written).",
                    device.getName(), row.getDeviceUserId(), written, wanted);
        }
        return new RevisionNotice(device.getPublicId(), cursor.getAppliedRevision());
    }

    /**
     * The reader already has this id. When that person is the same member (same face), retry at the
     * same id with the observed record as precondition — typical after an authority/profile publish
     * that expected an empty slot. Otherwise remember the id as blocked, allocate the next integer,
     * and publish a new revision. The caller does not choose the replacement id.
     */
    @Transactional
    public RevisionNotice occupied(Gateway gateway, ReportOccupied report) {
        Device device = ownedReader(gateway, report.deviceId());
        ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
        DesiredMemberProjection row = currentAt(device, report.revision());
        if (!report.deviceUserId().equals(row.getDeviceUserId())) {
            throw CommonExceptions.conflict("Occupied id is not the desired id");
        }
        DeviceObservedUser seen = observedUsers
                .findByDeviceIdAndDeviceUserId(device.getId(), report.deviceUserId())
                .orElse(null);
        if (samePersonOccupying(row, seen)) {
            long revision = cursor.bumpDesired();
            row.setRevision(revision);
            row.setExpected(ExpectedOccupant.of(seen));
            projections.save(row);
            notifyAfterCommit(device, revision);
            FlowLog.info("device",
                    "occupied id {} on reader={} is the same member; retry revision={} at same id",
                    report.deviceUserId(), device.getPublicId(), revision);
            return new RevisionNotice(device.getPublicId(), revision);
        }
        if (!blocked.existsByDeviceIdAndDeviceUserId(device.getId(), report.deviceUserId())) {
            blocked.save(new ReaderBlockedUser(device.getTenantId(), device.getId(), report.deviceUserId()));
        }
        String nextId = allocator.allocate(cursor);
        MemberDeviceMapping mapping = revisions.reload(
                mappings.findByDeviceIdAndMemberId(device.getId(), row.getMemberId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member mapping is missing")));
        mapping.setDeviceUserId(nextId);
        mappings.save(mapping);
        long revision = cursor.bumpDesired();
        row.setRevision(revision);
        row.setDeviceUserId(nextId);
        row.setExpected(ExpectedOccupant.ABSENT);
        projections.save(row);
        notifyAfterCommit(device, revision);
        FlowLog.info("device", "occupied id {} on reader={}; retry revision={} user={}",
                report.deviceUserId(), device.getPublicId(), revision, nextId);
        return new RevisionNotice(device.getPublicId(), revision);
    }

    /** Occupant face matches the desired member's face — not a stranger at the id. */
    private static boolean samePersonOccupying(DesiredMemberProjection row, DeviceObservedUser seen) {
        if (row.getMemberId() == null || seen == null || !row.isFacePresent()) {
            return false;
        }
        String desiredFace = row.getFaceSha256();
        String seenFace = seen.getFaceSha256();
        return desiredFace != null && !desiredFace.isBlank()
                && seenFace != null && !seenFace.isBlank()
                && desiredFace.equalsIgnoreCase(seenFace);
    }

    /** A reader of this gateway and the row that still carries {@code revision}, with the reader locked. */
    @Transactional
    public Located locate(Gateway gateway, String devicePublicId, long revision) {
        Device device = ownedReader(gateway, devicePublicId);
        revisions.lock(device.getTenantId(), device.getId());
        return new Located(device, currentAt(device, revision));
    }

    public record Located(Device device, DesiredMemberProjection row) {
    }

    /**
     * What the gateway must find at the id before it writes or removes: the stored precondition,
     * else the record this reader last verified there, else no one.
     */
    private ExpectedOccupant expected(DesiredMemberProjection row) {
        ExpectedOccupant stored = row.getExpected();
        if (stored != null) {
            return stored;
        }
        Optional<ExpectedOccupant> baseline = reviews.baseline(row.getDeviceId(), row.getDeviceUserId())
                .map(ExpectedOccupant::of);
        if (baseline.isPresent()) {
            return baseline.get();
        }
        // Profile publish with no baseline yet: if the reader already shows this member, expect them
        // so the gateway ReplaceUser instead of reporting Occupied and allocating a new id.
        if (row.getMemberId() != null && row.isFacePresent() && row.getFaceSha256() != null) {
            DeviceObservedUser seen = observedUsers
                    .findByDeviceIdAndDeviceUserId(row.getDeviceId(), row.getDeviceUserId())
                    .orElse(null);
            if (samePersonOccupying(row, seen)) {
                return ExpectedOccupant.of(seen);
            }
        }
        return ExpectedOccupant.ABSENT;
    }

    private record Held(Device device, ReaderRevision cursor, DesiredMemberProjection row,
                        MemberDeviceMapping mapping) {
    }

    /**
     * The member's rows on each reader of the gym with a gateway, lowest device id first, each read
     * under that reader's lock. Every such reader is locked, not only the ones this transaction's
     * snapshot shows the member on: a seed that is writing the member to another reader holds that
     * reader's lock until it commits, and the locking reads then see its rows.
     */
    private List<Held> inReaderOrder(Member member) {
        List<Device> readers = devices.findByTenantId(member.getTenantId()).stream()
                .filter(device -> device.getGatewayId() != null)
                .sorted(Comparator.comparing(Device::getId))
                .toList();
        List<Held> held = new ArrayList<>();
        for (Device device : readers) {
            ReaderRevision cursor = revisions.lock(device.getTenantId(), device.getId());
            MemberDeviceMapping mapping = current(
                    mappings.findByDeviceIdAndMemberId(device.getId(), member.getId()),
                    () -> mappings.lockByDeviceIdAndMemberId(device.getId(), member.getId()));
            DesiredMemberProjection row = current(
                    projections.findByDeviceIdAndMemberId(device.getId(), member.getId()),
                    () -> projections.lockByDeviceIdAndMemberId(device.getId(), member.getId()));
            if (mapping != null && row != null) {
                held.add(new Held(device, cursor, row, mapping));
            }
        }
        return held;
    }

    /** A row this transaction's snapshot shows is reread under the lock; otherwise a locking read finds it. */
    private <T> T current(Optional<T> seen, Supplier<Optional<T>> locked) {
        return seen.map(revisions::reload).or(locked).orElse(null);
    }

    /**
     * The row that still carries this revision. Call with the reader locked. A later revision of
     * the same row means this one was superseded and is no longer the desired member.
     */
    private DesiredMemberProjection currentAt(Device device, long revision) {
        DesiredMemberProjection row = projections.findByDeviceIdAndRevision(device.getId(), revision)
                .map(revisions::reload)
                .filter(found -> found.getRevision() == revision)
                .orElse(null);
        if (row == null) {
            throw CommonExceptions.conflict("Revision is not the desired member");
        }
        return row;
    }

    private void applyServer(DesiredMemberProjection row, Member member, String deviceUserId, MemberFace face) {
        int status = readerStatus(member);
        String fullName = member.getFullName();
        LocalDate today = LocalDate.now(READER_ZONE);
        var window = authorization.window(member, today);
        String from;
        String to;
        if (member.getAdoptedValidFrom() != null && member.getAdoptedValidTo() != null) {
            from = member.getAdoptedValidFrom();
            to = member.getAdoptedValidTo();
        } else if (window.isPresent()) {
            from = at(window.get().validFrom(), LocalTime.MIN);
            to = at(window.get().validTo(), LocalTime.of(23, 59, 59));
        } else {
            from = row.getValidFrom();
            to = row.getValidTo();
        }
        row.setPresentOnReader(true);
        row.setDeviceUserId(deviceUserId);
        row.setReaderName(szName(fullName));
        row.setReaderNameEx(szNameEx(fullName));
        row.setUserStatus(status);
        row.setValidFrom(from);
        row.setValidTo(to);
        row.setAuthority(authority(member));
        if (face == null) {
            row.setFacePresent(false);
            row.setObservedFaceSha256(null);
            return;
        }
        row.setFacePresent(true);
        row.setFaceSha256(face.getSha256());
        // Do not clear observedFaceSha256: reviews compare GetFace hashes; ACK refreshes it.
    }

    /**
     * Terminal authority for desired writes comes from the member field staff edit in the UI.
     * Import of a reader administrator sets that field via {@link #adoptFromReader}.
     */
    private String authority(Member member) {
        return member.getDeviceAuthority() == DeviceAuthority.ADMIN ? ADMIN : AUTHORITY;
    }

    static boolean isAdminAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return false;
        }
        String value = authority.trim();
        return ADMIN.equalsIgnoreCase(value) || "Administrators".equalsIgnoreCase(value);
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
        boolean adopted = member.getAdoptedValidFrom() != null && member.getAdoptedValidTo() != null;
        String fullName = member.getFullName();
        row.setRevision(revision);
        row.setDeviceUserId(deviceUserId);
        row.setPresentOnReader(true);
        row.setReaderName(szName(fullName));
        row.setReaderNameEx(szNameEx(fullName));
        row.setUserStatus(readerStatus(member));
        if (adopted) {
            row.setValidFrom(member.getAdoptedValidFrom());
            row.setValidTo(member.getAdoptedValidTo());
        } else {
            LocalDate from = window.map(DeviceAuthorizationService.AccessWindow::validFrom).orElse(today);
            LocalDate to = window.map(DeviceAuthorizationService.AccessWindow::validTo).orElse(today);
            row.setValidFrom(at(from, LocalTime.MIN));
            row.setValidTo(at(to, LocalTime.of(23, 59, 59)));
        }
        row.setAuthority(authority(member));
        row.setDoorNum(DOOR_NUM);
        row.setTimeSectionNum(TIME_SECTION_NUM);
        row.setFacePresent(true);
        row.setFaceSha256(face.getSha256());
    }

    private static void adopt(DesiredMemberProjection row, long revision, Member member, MemberFace face,
                              DeviceObservedUser observed) {
        LocalDate today = LocalDate.now(READER_ZONE);
        boolean named = observed.getReaderName() != null;
        row.setRevision(revision);
        row.setDeviceUserId(observed.getDeviceUserId());
        row.setPresentOnReader(true);
        row.setReaderName(named ? observed.getReaderName() : szName(member.getFullName()));
        row.setReaderNameEx(named ? observed.getReaderNameEx() : szNameEx(member.getFullName()));
        row.setUserStatus(observed.getUserStatus());
        row.setValidFrom(readerTime(observed.getValidFrom(), at(today, LocalTime.MIN)));
        row.setValidTo(readerTime(observed.getValidTo(), at(today, LocalTime.of(23, 59, 59))));
        row.setAuthority(isAdminAuthority(observed.getAuthority()) ? ADMIN
                : (observed.getAuthority() == null ? AUTHORITY : observed.getAuthority()));
        row.setDoorNum(observed.getDoorNum() == null ? DOOR_NUM : observed.getDoorNum());
        row.setTimeSectionNum(observed.getTimeSectionNum() == null ? TIME_SECTION_NUM : observed.getTimeSectionNum());
        row.setFacePresent(true);
        row.setFaceSha256(face.getSha256());
    }

    /** The reader's own time, in the form the gateway's read-back reports; null when unknown. */
    static String readerTime(String observed) {
        return readerTime(observed, null);
    }

    /** The reader's own time, in the form the gateway's read-back reports. */
    private static String readerTime(String observed, String fallback) {
        if (blank(observed)) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(observed)
                    .atZoneSameInstant(READER_ZONE)
                    .toOffsetDateTime()
                    .format(READER_TIME);
        } catch (DateTimeParseException e) {
            return fallback;
        }
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
                row.isFacePresent(),
                expected(row));
    }

    private String faceBytes(DesiredMemberProjection row) {
        MemberFace photo = faces.findByMemberId(row.getMemberId())
                .orElseThrow(() -> CommonExceptions.conflict("Desired member has no face"));
        return Base64.getEncoder().encodeToString(storage.read(photo.getObjectKey()));
    }

    private boolean matches(DesiredMemberProjection row, AcknowledgeRevision ack) {
        return mismatchReason(row, ack) == null;
    }

    /**
     * Null when the ack matches. Otherwise a short reason (field names) for the 409 detail — gateway
     * local verify already uses day-level validity and may report a re-encoded face hash.
     */
    private String mismatchReason(DesiredMemberProjection row, AcknowledgeRevision ack) {
        if (!row.isPresentOnReader()) {
            if (Boolean.FALSE.equals(ack.present())
                    && "NO_RECORD".equals(ack.failCode())
                    && ack.deviceUserId().equals(row.getDeviceUserId())) {
                return null;
            }
            return "absence";
        }
        String user = userMismatch(row, ack);
        if (user != null) {
            return user;
        }
        if (row.isFacePresent()) {
            return faceHashMatches(ack) ? null : "face";
        }
        return faceCleared(ack) ? null : "faceCleared";
    }

    private static String userMismatch(DesiredMemberProjection row, AcknowledgeRevision ack) {
        if (Boolean.FALSE.equals(ack.present())
                || ack.name() == null
                || ack.userStatus() == null
                || ack.validFrom() == null
                || ack.validTo() == null) {
            return "incomplete";
        }
        if (!ack.deviceUserId().equals(row.getDeviceUserId())) {
            return "deviceUserId";
        }
        if (!nameMatches(ack.name(), row.getReaderName(), row.getReaderNameEx())) {
            return "name";
        }
        if (!sameNameEx(ack.nameEx(), row.getReaderNameEx())
                && !(blank(ack.nameEx())
                        && row.getReaderNameEx() != null
                        && row.getReaderNameEx().equals(ack.name()))) {
            return "nameEx";
        }
        if (ack.userStatus() != row.getUserStatus()) {
            return "userStatus";
        }
        if (!sameValidityDay(ack.validFrom(), row.getValidFrom())) {
            return "validFrom";
        }
        if (!sameValidityDay(ack.validTo(), row.getValidTo())) {
            return "validTo";
        }
        return null;
    }

    /** Any 64-char hash: exact desired bytes, or a reader re-encode after a local gateway verify. */
    private static boolean faceHashMatches(AcknowledgeRevision ack) {
        return ack.faceSha256() != null
                && ack.faceSha256().length() == 64
                && (ack.failCode() == null || ack.failCode().isBlank());
    }

    /** A removed photo reads back as UNKNOWN with no bytes. NO_RECORD means the user is gone. */
    private static boolean faceCleared(AcknowledgeRevision ack) {
        return (ack.faceSha256() == null || ack.faceSha256().isBlank())
                && "UNKNOWN".equals(ack.failCode());
    }

    /**
     * TrueFace reports the long name in {@code name} when {@code bUseNameEx} is set; short names stay
     * in {@code readerName}. Either form is enough when it matches the desired projection.
     */
    private static boolean nameMatches(String reported, String readerName, String readerNameEx) {
        return reported.equals(readerName)
                || (readerNameEx != null && !readerNameEx.isBlank() && reported.equals(readerNameEx));
    }

    private static boolean sameNameEx(String reported, String desired) {
        String left = reported == null || reported.isBlank() ? null : reported;
        String right = desired == null || desired.isBlank() ? null : desired;
        return Objects.equals(left, right);
    }

    /** Validity is a calendar day on the reader clock; time-of-day is not part of the match. */
    static boolean sameValidityDay(String reported, String desired) {
        LocalDate left = validityDay(reported);
        LocalDate right = validityDay(desired);
        return left != null && left.equals(right);
    }

    private static LocalDate validityDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(READER_ZONE).toLocalDate();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    Device ownedReader(Gateway gateway, String devicePublicId) {
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
