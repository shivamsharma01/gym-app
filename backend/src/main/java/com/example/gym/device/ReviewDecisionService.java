package com.example.gym.device;

import com.example.gym.common.error.CommonExceptions;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.device.dto.ReviewItemView;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.face.GatewayFaceUpload;
import com.example.gym.face.MemberFace;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.DeviceAuthority;
import com.example.gym.member.Member;
import com.example.gym.member.MemberService;
import com.example.gym.member.MemberStatus;
import com.example.gym.member.dto.MemberRequests.CreateMember;
import com.example.gym.security.SecurityUtils;
import com.example.gym.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff choose what a reader should hold. Each choice is a desired revision on the existing apply
 * path. Link and create keep the reader's device user id. Create also writes that new member onto
 * every other reader of the gym. Reject creates no member.
 */
@Service
public class ReviewDecisionService {

    private final DeviceRepository devices;
    private final DeviceReviewItemRepository reviews;
    private final ReaderReviewService readerReviews;
    private final PendingEnrollmentRepository enrollments;
    private final PendingEnrollmentService observations;
    private final MemberService members;
    private final MemberFaceService faces;
    private final DesiredProjectionService desired;
    private final ReaderReconcileService readers;

    public ReviewDecisionService(DeviceRepository devices,
                                 DeviceReviewItemRepository reviews,
                                 ReaderReviewService readerReviews,
                                 PendingEnrollmentRepository enrollments,
                                 PendingEnrollmentService observations,
                                 MemberService members,
                                 MemberFaceService faces,
                                 DesiredProjectionService desired,
                                 ReaderReconcileService readers) {
        this.devices = devices;
        this.reviews = reviews;
        this.readerReviews = readerReviews;
        this.enrollments = enrollments;
        this.observations = observations;
        this.members = members;
        this.faces = faces;
        this.desired = desired;
        this.readers = readers;
    }

    @Transactional(readOnly = true)
    public List<ReviewItemView> open(Long tenantId) {
        List<ReviewItemView> rows = new ArrayList<>();
        for (DeviceReviewItem item : reviews.findByTenantIdAndResolvedFalse(tenantId)) {
            rows.add(reviewView(item));
        }
        for (PendingEnrollment enrollment : enrollments.findByTenantIdAndResolvedFalse(tenantId)) {
            rows.add(enrollmentView(enrollment));
        }
        return rows;
    }

    @Transactional
    public ReviewItemView acceptServer(String publicId, Long tenantId) {
        DeviceReviewItem item = openReview(publicId, tenantId);
        if (item.isSuspended()) {
            throw CommonExceptions.conflict("The reader holds someone else at this id; restore or remove the member");
        }
        Member member = members.getById(item.getMemberId(), tenantId);
        Device device = device(item.getDeviceId(), tenantId);
        long revision = desired.republish(member, device, ExpectedOccupant.of(item));
        // Approved server record becomes desired on every other mapped reader too.
        desired.publishAccess(member, device.getId());
        item.decide(ReviewDecisions.ACCEPT_SERVER, actor(), readerShown(item), serverShown(item), revision);
        return reviewView(reviews.save(item));
    }

    /**
     * Keep the fields the reader shows as the member of record, apply them on this reader, then
     * publish that approved state onto every other mapped reader. The hold is released; the gateway
     * acks this reader without overwriting what it already shows.
     */
    @Transactional
    public ReviewItemView acceptReader(String publicId, Long tenantId) {
        DeviceReviewItem item = openReview(publicId, tenantId);
        if (item.isSuspended()) {
            throw CommonExceptions.conflict("The reader holds someone else at this id; restore or remove the member");
        }
        if (item.isReaderAbsent()) {
            throw CommonExceptions.conflict("The reader shows nobody at this id; remove the member or overwrite with server");
        }
        Member member = members.getById(item.getMemberId(), tenantId);
        Device device = device(item.getDeviceId(), tenantId);
        adoptReaderDecisionOntoMember(member, item);
        adoptReaderFaceIfPresent(member, device, item.getReaderFaceSha256(), tenantId);
        long revision = desired.acceptReaderValues(member, device, item);
        desired.publishAccess(member, device.getId());
        desired.publishFace(member);
        item.decide(ReviewDecisions.ACCEPT_READER, actor(), serverShown(item), readerShown(item), revision);
        return reviewView(reviews.save(item));
    }

    /**
     * Staff say the person at the suspended id is still this member: adopt the reader's name,
     * dates, and photo onto the member, remapping that same device user id, then publish to other
     * mapped readers. Use this after a rename/photo edit on the reader was mistaken for a stranger.
     */
    @Transactional
    public ReviewItemView adoptReader(String publicId, Long tenantId) {
        DeviceReviewItem item = openReview(publicId, tenantId);
        if (!item.isSuspended()) {
            throw CommonExceptions.conflict("Adopt reader applies only when the member was suspended on this reader");
        }
        if (item.isReaderAbsent()) {
            throw CommonExceptions.conflict("The reader shows nobody at this id");
        }
        Member member = members.getById(item.getMemberId(), tenantId);
        Device device = device(item.getDeviceId(), tenantId);
        PendingEnrollment enrollment = enrollments
                .findByDeviceIdAndDeviceUserId(device.getId(), item.getDeviceUserId())
                .filter(row -> !row.isResolved())
                .orElseThrow(() -> CommonExceptions.conflict(
                        "No open enrollment at this reader id; create or link from People the server does not know"));
        DeviceObservedUser observed = requireObserved(enrollment);
        adoptObservedOntoMember(member, observed);
        MemberFace face = faces.findUnconsumedByHash(tenantId, observed.getFaceSha256())
                .map(upload -> faces.applyFromDevice(member, upload, device.getId(), Instant.now()))
                .orElseGet(() -> faces.find(member.getId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member has no face")));
        long revision = desired.adoptFromReader(member, device, face, observed);
        desired.publishAccess(member, device.getId());
        item.decide(ReviewDecisions.ACCEPT_READER, actor(), serverShown(item), readerShown(item), revision);
        enrollment.decide(ReviewDecisions.LINK, actor(), "PENDING", member.getPublicId(), revision);
        enrollments.save(enrollment);
        return reviewView(reviews.save(item));
    }

    @Transactional
    public ReviewItemView restore(String publicId, Long tenantId) {
        DeviceReviewItem item = openReview(publicId, tenantId);
        Member member = members.getById(item.getMemberId(), tenantId);
        Device device = device(item.getDeviceId(), tenantId);
        if (item.isSuspended()) {
            return restoreSuspended(item, member, device);
        }
        // Same write as accept-server for field conflicts; kept for older clients.
        long revision = desired.republish(member, device, ExpectedOccupant.of(item));
        desired.publishAccess(member, device.getId());
        String prior = item.isReaderAbsent() ? "absent" : readerShown(item);
        item.decide(ReviewDecisions.RESTORE, actor(), prior, serverShown(item), revision);
        return reviewView(reviews.save(item));
    }

    @Transactional
    public ReviewItemView remove(String publicId, Long tenantId) {
        DeviceReviewItem item = openReview(publicId, tenantId);
        if (item.isSuspended()) {
            item.decideWithoutWrite(ReviewDecisions.REMOVE, actor(), readerShown(item), "removed");
            return reviewView(reviews.save(item));
        }
        Member member = members.getById(item.getMemberId(), tenantId);
        Device device = device(item.getDeviceId(), tenantId);
        long revision = desired.publishRemoval(member, device, ExpectedOccupant.of(item));
        if (revision == 0) {
            throw CommonExceptions.conflict("Member is already absent from this reader");
        }
        item.decide(ReviewDecisions.REMOVE, actor(), readerShown(item), "removed", revision);
        return reviewView(reviews.save(item));
    }

    @Transactional
    public ReviewItemView link(String publicId, String memberPublicId, Long tenantId) {
        if (memberPublicId == null || memberPublicId.isBlank()) {
            throw CommonExceptions.badRequest("memberId is required (the member UUID from the member page, not the reader id)");
        }
        String trimmed = memberPublicId.trim();
        PendingEnrollment enrollment = openEnrollment(publicId, tenantId);
        if (trimmed.equals(enrollment.getDeviceUserId())) {
            throw CommonExceptions.badRequest(
                    "memberId must be the server member UUID (from the member page URL), not reader id "
                            + trimmed);
        }
        // Enrollment came from the reader: keep reader fields on the member, do not overwrite the reader
        // with the previous server record.
        Member member = members.getByPublicId(trimmed, tenantId);
        Device device = device(enrollment.getDeviceId(), tenantId);
        DeviceObservedUser observed = requireObserved(enrollment);
        adoptObservedOntoMember(member, observed);
        MemberFace face = faces.findUnconsumedByHash(tenantId, observed.getFaceSha256())
                .map(upload -> faces.applyFromDevice(member, upload, device.getId(), Instant.now()))
                .orElseGet(() -> faces.find(member.getId())
                        .orElseThrow(() -> CommonExceptions.conflict("Member has no face")));
        long revision = desired.adoptFromReader(member, device, face, observed);
        desired.publishAccess(member, device.getId());
        desired.publishFace(member);
        for (DeviceReviewItem suspended : reviews.findByDeviceIdAndMemberIdAndKindAndResolvedFalse(
                device.getId(), member.getId(), DeviceReviewItem.SUSPENDED)) {
            suspended.decideWithoutWrite(ReviewDecisions.LINK, actor(),
                    "someone else at " + suspended.getDeviceUserId(), "linked at " + enrollment.getDeviceUserId());
            reviews.save(suspended);
        }
        enrollment.decide(ReviewDecisions.LINK, actor(), "PENDING", member.getPublicId(), revision);
        return enrollmentView(enrollments.save(enrollment));
    }

    @Transactional
    public ReviewItemView create(String publicId, Long tenantId) {
        PendingEnrollment enrollment = openEnrollment(publicId, tenantId);
        DeviceObservedUser observed = requireObserved(enrollment);
        GatewayFaceUpload upload = faces.findUnconsumedByHash(tenantId, observed.getFaceSha256())
                .orElseThrow(() -> CommonExceptions.conflict("The reader face is not on the server"));
        String[] name = personName(ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()));
        Member member = members.saveNew(new CreateMember(
                name[0], name[1], null, null, null, null, null, null, null), tenantId);
        if (observed.getUserStatus() != DesiredProjectionService.USER_STATUS) {
            member.setStatus(MemberStatus.INACTIVE);
        }
        MemberFace face = faces.applyFromDevice(member, upload, enrollment.getDeviceId(), Instant.now());
        Device device = device(enrollment.getDeviceId(), tenantId);
        readers.lockReaders(tenantId);
        long revision = desired.adoptFromReader(member, device, face, observed);
        readers.reconcile(member);
        enrollment.decide(ReviewDecisions.CREATE, actor(), "PENDING", member.getPublicId(), revision);
        return enrollmentView(enrollments.save(enrollment));
    }

    /**
     * CONFLICT on a mapped id: staff say the person on the reader is not this member. Release the
     * former member from this reader and create a new member that keeps the reader's id and fields.
     */
    @Transactional
    public ReviewItemView createAsNew(String publicId, Long tenantId) {
        DeviceReviewItem item = openReview(publicId, tenantId);
        if (item.isSuspended()) {
            throw CommonExceptions.conflict("Use Create on the enrollment at this reader id");
        }
        if (item.isReaderAbsent()) {
            throw CommonExceptions.conflict("The reader shows nobody at this id");
        }
        Device device = device(item.getDeviceId(), tenantId);
        DeviceObservedUser observed = observations.snapshot(device.getId(), item.getDeviceUserId());
        if (observed == null) {
            throw CommonExceptions.conflict("Reader observation is missing");
        }
        GatewayFaceUpload upload = faces.findUnconsumedByHash(tenantId, observed.getFaceSha256())
                .orElseThrow(() -> CommonExceptions.conflict("The reader face is not on the server"));
        String prior = serverShown(item);
        readerReviews.releaseMappedMember(device, item);
        String[] name = personName(ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()));
        Member member = members.saveNew(new CreateMember(
                name[0], name[1], null, null, null, null, null, null, null), tenantId);
        if (observed.getUserStatus() != DesiredProjectionService.USER_STATUS) {
            member.setStatus(MemberStatus.INACTIVE);
        }
        MemberFace face = faces.applyFromDevice(member, upload, device.getId(), Instant.now());
        readers.lockReaders(tenantId);
        long revision = desired.adoptFromReader(member, device, face, observed);
        readers.reconcile(member);
        item.setMemberId(member.getId());
        item.decide(ReviewDecisions.CREATE, actor(), prior, member.getPublicId(), revision);
        return reviewView(reviews.save(item));
    }

    @Transactional
    public ReviewItemView reject(String publicId, Long tenantId) {
        PendingEnrollment enrollment = openEnrollment(publicId, tenantId);
        DeviceObservedUser observed = requireObserved(enrollment);
        Device device = device(enrollment.getDeviceId(), tenantId);
        long revision = desired.publishAbsence(device, observed);
        enrollment.decide(ReviewDecisions.REJECT, actor(), "PENDING", "removed", revision);
        return enrollmentView(enrollments.save(enrollment));
    }

    /**
     * The member was taken off this reader because someone else holds the old id. Restoring writes
     * the member under a new id the server allocates; the old id stays with the reader person.
     */
    private ReviewItemView restoreSuspended(DeviceReviewItem item, Member member, Device device) {
        MemberFace face = faces.find(member.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Member has no face"));
        long revision = desired.write(member, device, face);
        String previous = item.getDeviceUserId();
        item.setDeviceUserId(desired.mappedId(device, member));
        item.decide(ReviewDecisions.RESTORE, actor(), "someone else at " + previous, serverShown(item), revision);
        return reviewView(reviews.save(item));
    }

    private DeviceReviewItem openReview(String publicId, Long tenantId) {
        DeviceReviewItem item = reviews.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Review item"));
        TenantGuard.check(item.getTenantId(), tenantId, "Review item");
        requireOpen(item.isResolved(), item.getDecision(), item.getVerificationError());
        return item;
    }

    private PendingEnrollment openEnrollment(String publicId, Long tenantId) {
        PendingEnrollment enrollment = enrollments.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("Pending enrollment"));
        TenantGuard.check(enrollment.getTenantId(), tenantId, "Pending enrollment");
        requireOpen(enrollment.isResolved(), enrollment.getDecision(), enrollment.getVerificationError());
        return enrollment;
    }

    private static void requireOpen(boolean resolved, String decision, String verificationError) {
        if (resolved) {
            throw CommonExceptions.conflict("Review item is closed");
        }
        if (decision != null && (verificationError == null || verificationError.isBlank())) {
            throw CommonExceptions.conflict("Decision is waiting for the reader");
        }
    }

    private Device device(Long deviceId, Long tenantId) {
        Device device = devices.findById(deviceId)
                .orElseThrow(() -> CommonExceptions.notFound("Reader"));
        TenantGuard.check(device.getTenantId(), tenantId, "Reader");
        return device;
    }

    private DeviceObservedUser requireObserved(PendingEnrollment enrollment) {
        DeviceObservedUser observed = observations.snapshot(enrollment.getDeviceId(), enrollment.getDeviceUserId());
        if (observed == null) {
            throw CommonExceptions.conflict("Reader observation is missing");
        }
        return observed;
    }

    private ReviewItemView reviewView(DeviceReviewItem item) {
        Device device = devices.findById(item.getDeviceId()).orElse(null);
        Member member = members.getById(item.getMemberId(), item.getTenantId());
        return new ReviewItemView(
                item.getPublicId(),
                item.isSuspended() ? ReviewDecisions.SUSPENDED : ReviewDecisions.REVIEW,
                device == null ? "" : device.getPublicId(),
                item.getDeviceUserId(),
                serverShown(item),
                readerShown(item),
                ReaderReviewService.shown(item.getBaselineName(), item.getBaselineNameEx()),
                item.isReaderAbsent(),
                !item.isResolved(),
                item.getDecision(),
                item.getActor(),
                item.getPriorState(),
                item.getChosenState(),
                item.getDecisionRevision(),
                item.getVerificationError(),
                item.getServerValidFrom(),
                item.getServerValidTo(),
                item.getReaderValidFrom(),
                item.getReaderValidTo(),
                item.getBaselineValidFrom(),
                item.getBaselineValidTo(),
                item.getServerUserStatus(),
                item.getReaderUserStatus(),
                item.getBaselineUserStatus(),
                item.getReaderFaceSha256() != null
                        && !item.getReaderFaceSha256().equalsIgnoreCase(item.getServerFaceSha256()),
                device == null ? null : device.getName(),
                member.getPublicId(),
                member.getFullName(),
                null,
                null);
    }

    private ReviewItemView enrollmentView(PendingEnrollment enrollment) {
        DeviceObservedUser observed = observations.snapshot(enrollment.getDeviceId(), enrollment.getDeviceUserId());
        String reader = observed == null
                ? enrollment.getDeviceUserId()
                : ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx());
        Device device = devices.findById(enrollment.getDeviceId()).orElse(null);
        return new ReviewItemView(
                enrollment.getPublicId(),
                ReviewDecisions.ENROLLMENT,
                device == null ? "" : device.getPublicId(),
                enrollment.getDeviceUserId(),
                null,
                reader,
                null,
                false,
                !enrollment.isResolved(),
                enrollment.getDecision(),
                enrollment.getActor(),
                enrollment.getPriorState(),
                enrollment.getChosenState(),
                enrollment.getDecisionRevision(),
                enrollment.getVerificationError(),
                null,
                null,
                observed == null ? null : observed.getValidFrom(),
                observed == null ? null : observed.getValidTo(),
                null,
                null,
                null,
                observed == null ? null : observed.getUserStatus(),
                null,
                false,
                device == null ? null : device.getName(),
                null,
                null,
                null,
                null);
    }

    private static String readerShown(DeviceReviewItem item) {
        return ReaderReviewService.shown(item.getReaderName(), item.getReaderNameEx());
    }

    private static String serverShown(DeviceReviewItem item) {
        return ReaderReviewService.shown(item.getServerName(), item.getServerNameEx());
    }

    private static String actor() {
        return SecurityUtils.currentPrincipal().getUsername();
    }

    /** Copy the approved reader fields onto the member so other readers get the same record. */
    private static void adoptReaderDecisionOntoMember(Member member, DeviceReviewItem item) {
        String[] name = personName(readerShown(item));
        member.setFirstName(name[0]);
        member.setLastName(name[1]);
        if (item.getReaderUserStatus() != null) {
            member.setStatus(item.getReaderUserStatus() == DesiredProjectionService.USER_STATUS
                    ? MemberStatus.ACTIVE
                    : MemberStatus.INACTIVE);
        }
        if (item.getReaderAuthority() != null && !item.getReaderAuthority().isBlank()) {
            member.setDeviceAuthority(DesiredProjectionService.isAdminAuthority(item.getReaderAuthority())
                    ? DeviceAuthority.ADMIN
                    : DeviceAuthority.USER);
        }
        String from = DesiredProjectionService.readerTime(item.getReaderValidFrom());
        String to = DesiredProjectionService.readerTime(item.getReaderValidTo());
        if (from != null && to != null) {
            member.adoptReaderWindow(from, to);
        }
        member.setProfileChangedAt(Instant.now());
    }

    private void adoptReaderFaceIfPresent(Member member, Device device, String faceSha256, Long tenantId) {
        if (faceSha256 == null || faceSha256.length() != 64) {
            return;
        }
        faces.findUnconsumedByHash(tenantId, faceSha256)
                .ifPresent(upload -> faces.applyFromDevice(member, upload, device.getId(), Instant.now()));
    }

    private static void adoptObservedOntoMember(Member member, DeviceObservedUser observed) {
        String[] name = personName(ReaderReviewService.shown(observed.getReaderName(), observed.getReaderNameEx()));
        member.setFirstName(name[0]);
        member.setLastName(name[1]);
        member.setStatus(observed.getUserStatus() == DesiredProjectionService.USER_STATUS
                ? MemberStatus.ACTIVE
                : MemberStatus.INACTIVE);
        if (observed.getAuthority() != null && !observed.getAuthority().isBlank()) {
            member.setDeviceAuthority(DesiredProjectionService.isAdminAuthority(observed.getAuthority())
                    ? DeviceAuthority.ADMIN
                    : DeviceAuthority.USER);
        }
        String from = DesiredProjectionService.readerTime(observed.getValidFrom());
        String to = DesiredProjectionService.readerTime(observed.getValidTo());
        if (from != null && to != null) {
            member.adoptReaderWindow(from, to);
        }
        member.setProfileChangedAt(Instant.now());
    }

    private static String[] personName(String shown) {
        String trimmed = shown == null ? "" : shown.trim();
        if (trimmed.isEmpty()) {
            throw CommonExceptions.badRequest("Reader name is empty");
        }
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return new String[] {cut(trimmed, 80), null};
        }
        String last = trimmed.substring(space + 1).trim();
        return new String[] {cut(trimmed.substring(0, space), 80), last.isEmpty() ? null : cut(last, 80)};
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
