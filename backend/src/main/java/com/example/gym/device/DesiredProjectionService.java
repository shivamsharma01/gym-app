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
                                    GatewaySessionRegistry sessions) {
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
    }

    @Transactional
    public long write(Member member, Device device, MemberFace face) {
        if (!device.isProjectionEnabled()) {
            throw CommonExceptions.badRequest("Reader is not flagged for desired state");
        }
        if (device.getGatewayId() == null) {
            throw CommonExceptions.badRequest("Reader has no gateway");
        }
        if (mappings.existsByDeviceIdAndMemberId(device.getId(), member.getId())) {
            throw CommonExceptions.conflict("Member is already mapped to this reader");
        }
        String deviceUserId = allocator.allocate(device.getId());
        mappings.save(new MemberDeviceMapping(
                member.getTenantId(), member.getId(), device.getId(), deviceUserId));

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
            throw CommonExceptions.conflict("Read-back does not match the desired member");
        }
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
        row.setFaceSha256(face.getSha256());
    }

    private DesiredItem item(DesiredMemberProjection row) {
        MemberFace face = faces.findByMemberId(row.getMemberId())
                .orElseThrow(() -> CommonExceptions.conflict("Desired member has no face"));
        byte[] jpeg = storage.read(face.getObjectKey());
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
                row.getFaceSha256(),
                Base64.getEncoder().encodeToString(jpeg));
    }

    private boolean matches(DesiredMemberProjection row, AcknowledgeRevision ack) {
        return ack.deviceUserId().equals(row.getDeviceUserId())
                && ack.name().equals(row.getReaderName())
                && sameNameEx(ack.nameEx(), row.getReaderNameEx())
                && ack.userStatus() == row.getUserStatus()
                && ack.validFrom().equals(row.getValidFrom())
                && ack.validTo().equals(row.getValidTo())
                && ack.faceSha256().equalsIgnoreCase(row.getFaceSha256());
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
        if (!device.isProjectionEnabled()) {
            throw CommonExceptions.notFound("Reader");
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
