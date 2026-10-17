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
import com.example.gym.member.Member;
import com.example.gym.member.MemberNumbers;
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
 * path. Link and create keep the reader's device user id. Reject creates no member.
 */
@Service
public class ReviewDecisionService {

    private final DeviceRepository devices;
    private final DeviceReviewItemRepository reviews;
    private final PendingEnrollmentRepository enrollments;
    private final PendingEnrollmentService observations;
    private final MemberService members;
    private final MemberNumbers memberNumbers;
    private final MemberFaceService faces;
    private final DesiredProjectionService desired;

    public ReviewDecisionService(DeviceRepository devices,
                                 DeviceReviewItemRepository reviews,
                                 PendingEnrollmentRepository enrollments,
                                 PendingEnrollmentService observations,
                                 MemberService members,
                                 MemberNumbers memberNumbers,
                                 MemberFaceService faces,
                                 DesiredProjectionService desired) {
        this.devices = devices;
        this.reviews = reviews;
        this.enrollments = enrollments;
        this.observations = observations;
        this.members = members;
        this.memberNumbers = memberNumbers;
        this.faces = faces;
        this.desired = desired;
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
        item.decide(ReviewDecisions.ACCEPT_SERVER, actor(), readerShown(item), serverShown(item), revision);
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
        long revision = desired.republish(member, device, ExpectedOccupant.of(item));
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
        PendingEnrollment enrollment = openEnrollment(publicId, tenantId);
        Member member = members.getByPublicId(memberPublicId, tenantId);
        MemberFace face = faces.find(member.getId())
                .orElseThrow(() -> CommonExceptions.conflict("Member has no face"));
        Device device = device(enrollment.getDeviceId(), tenantId);
        long revision = desired.writeKeepingId(member, device, face, requireObserved(enrollment));
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
        String serial = memberNumbers.serialForImport(enrollment.getDeviceUserId());
        Member member = members.saveNew(new CreateMember(
                name[0], name[1], null, null, null, null, null, null, null, serial), tenantId);
        if (observed.getUserStatus() != DesiredProjectionService.USER_STATUS) {
            member.setStatus(MemberStatus.INACTIVE);
        }
        MemberFace face = faces.applyFromDevice(member, upload, enrollment.getDeviceId(), Instant.now());
        Device device = device(enrollment.getDeviceId(), tenantId);
        long revision = desired.adoptFromReader(member, device, face, observed);
        enrollment.decide(ReviewDecisions.CREATE, actor(), "PENDING", member.getPublicId(), revision);
        return enrollmentView(enrollments.save(enrollment));
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
