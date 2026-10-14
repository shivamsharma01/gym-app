package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.support.AbstractReaderIT;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rules that hold across readers: a device user id means something only on the reader that maps
 * it, a frozen member stays on the reader disabled, removal is a different operation from freeze,
 * and a wiped reader is put back to the server record through staff decisions.
 */
class ReaderInvariantsIT extends AbstractReaderIT {

    private String entranceId;
    private String exitId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("invariants");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
    }

    @Test
    void aDeviceUserIdResolvesToAMemberOnlyOnTheReaderThatMapsIt() throws Exception {
        Member asha = onlyOn(exitId, "Asha", "Rao", "INV-ASHA", "9101");
        ackEverything(exitId);
        String ashaOnExit = projection(exitId, asha).getDeviceUserId();
        Instant at = Instant.parse("2026-10-11T05:00:00Z");

        punch(entranceId, ashaOnExit, at, 1);
        punch(exitId, ashaOnExit, at.plusSeconds(60), 1);

        assertThat(eventOn(entranceId).getMemberId()).as("the same id on another reader is not Asha").isNull();
        assertThat(eventOn(exitId).getMemberId()).isEqualTo(asha.getId());

        readerSays(entranceId, person(ashaOnExit, "Someone Else", 0, "2026-01-01T00:00:00+05:30",
                "2026-12-31T23:59:59+05:30", null));

        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(entranceId), ashaOnExit))
                .hasValueSatisfying(enrollment -> assertThat(enrollment.isResolved()).isFalse());
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(readerPk(entranceId), ashaOnExit))
                .isEmpty();
        assertThat(deviceReviewItemRepository.count()).isZero();
        assertThat(memberRepository.findById(asha.getId()).orElseThrow().getFullName()).isEqualTo("Asha Rao");
        assertThat(memberDeviceMappingRepository.findByMemberId(asha.getId())).hasSize(1);
    }

    @Test
    void aFrozenMemberStaysOnEveryReaderDisabledUnderTheSameIds() throws Exception {
        Member ria = onlyOn(entranceId, "Ria", "Sen", "INV-RIA", "9102");
        desiredProjectionService.write(ria, deviceRepository.findByPublicId(exitId).orElseThrow(),
                memberFaceRepository.findByMemberId(ria.getId()).orElseThrow());
        String membership = paidMembership(ria);
        ackEverything(entranceId);
        ackEverything(exitId);
        DesiredMemberProjection entranceBefore = projection(entranceId, ria);
        DesiredMemberProjection exitBefore = projection(exitId, ria);
        assertThat(entranceBefore.getUserStatus()).isZero();

        postJson("/api/v1/memberships/" + membership + "/freeze", null).andExpect(status().isOk());

        for (String reader : List.of(entranceId, exitId)) {
            DesiredMemberProjection frozen = projection(reader, ria);
            DesiredMemberProjection before = reader.equals(entranceId) ? entranceBefore : exitBefore;
            assertThat(frozen.isPresentOnReader()).isTrue();
            assertThat(frozen.isFacePresent()).isTrue();
            assertThat(frozen.getUserStatus()).isEqualTo(1);
            assertThat(frozen.getDeviceUserId()).isEqualTo(before.getDeviceUserId());
            assertThat(frozen.getRevision()).isGreaterThan(before.getRevision());
        }
        assertThat(memberDeviceMappingRepository.findByMemberId(ria.getId())).hasSize(2);
        ackEverything(entranceId);
        ackEverything(exitId);

        postJson("/api/v1/memberships/" + membership + "/unfreeze", null).andExpect(status().isOk());
        assertThat(projection(entranceId, ria).getUserStatus()).isZero();
        assertThat(projection(entranceId, ria).getDeviceUserId()).isEqualTo(entranceBefore.getDeviceUserId());
    }

    @Test
    void freezeDisablesOnEveryReaderWhileRemovalTakesTheMemberOffOneReaderOnly() throws Exception {
        Member ria = onBothReaders("Ria", "INV-FREEZE", "9103");
        Member om = onBothReaders("Om", "INV-REMOVE", "9104");
        String riaMembership = paidMembership(ria);
        String omMembership = paidMembership(om);
        ackEverything(entranceId);
        ackEverything(exitId);

        postJson("/api/v1/memberships/" + riaMembership + "/freeze", null).andExpect(status().isOk());
        postJson("/api/v1/members/" + om.getPublicId() + "/device-sync/" + entranceId + "/remove", null)
                .andExpect(status().isAccepted());

        DesiredMemberProjection riaEntrance = projection(entranceId, ria);
        assertThat(riaEntrance.isPresentOnReader()).isTrue();
        assertThat(riaEntrance.getUserStatus()).isEqualTo(1);
        assertThat(projection(exitId, ria).getUserStatus()).isEqualTo(1);
        assertThat(membershipRepository.findByPublicIdAndDeletedFalse(riaMembership).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.FROZEN);

        DesiredMemberProjection omEntrance = projection(entranceId, om);
        assertThat(omEntrance.isPresentOnReader()).isFalse();
        DesiredMemberProjection omExit = projection(exitId, om);
        assertThat(omExit.isPresentOnReader()).isTrue();
        assertThat(omExit.getUserStatus()).isZero();
        assertThat(memberRepository.findById(om.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(membershipRepository.findByPublicIdAndDeletedFalse(omMembership).orElseThrow().getStatus())
                .isEqualTo(MembershipStatus.ACTIVE);

        ackEverything(entranceId);
        trustedRoster(entranceId, asDesired(projection(entranceId, ria), 1, riaEntrance.getFaceSha256()));
        assertThat(deviceReviewItemRepository.count())
                .as("a removed member missing from the list is the removal, not a difference")
                .isZero();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(entranceId), om.getId()))
                .hasValueSatisfying(row -> assertThat(row.isPresentOnReader()).isFalse());
    }

    @Test
    void aWipedReaderIsPutBackToTheServerRecordOnItsOwnIdsThroughStaffDecisions() throws Exception {
        Member asha = onlyOn(entranceId, "Asha", "Rao", "INV-WIPE-A", "9105");
        Member ravi = onlyOn(entranceId, "Ravi", "Iyer", "INV-WIPE-R", "9106");
        ackEverything(entranceId);
        DesiredMemberProjection ashaBefore = projection(entranceId, asha);
        DesiredMemberProjection raviBefore = projection(entranceId, ravi);

        trustedRoster(entranceId);

        List<DeviceReviewItem> absent = deviceReviewItemRepository.findAll();
        assertThat(absent).hasSize(2).allSatisfy(item -> {
            assertThat(item.isReaderAbsent()).isTrue();
            assertThat(item.isResolved()).isFalse();
        });
        assertThat(projection(entranceId, asha).getRevision()).as("nothing is written before staff decide")
                .isEqualTo(ashaBefore.getRevision());
        assertThat(memberDeviceMappingRepository.findAll()).hasSize(2);
        assertThat(memberRepository.findById(asha.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);

        for (DeviceReviewItem item : absent) {
            postJson("/api/v1/reviews/" + item.getPublicId() + "/restore", "{}").andExpect(status().isOk());
        }
        DesiredMemberProjection ashaRestored = projection(entranceId, asha);
        assertThat(ashaRestored.getRevision()).isGreaterThan(ashaBefore.getRevision());
        assertThat(ashaRestored.getDeviceUserId()).isEqualTo(ashaBefore.getDeviceUserId());
        assertThat(ashaRestored.isPresentOnReader()).isTrue();
        assertThat(projection(entranceId, ravi).getDeviceUserId()).isEqualTo(raviBefore.getDeviceUserId());
        ackEverything(entranceId);
        assertThat(deviceReviewItemRepository.findAll()).allSatisfy(item -> assertThat(item.isResolved()).isTrue());

        trustedRoster(entranceId,
                asDesired(ashaRestored, 0, ashaRestored.getFaceSha256()),
                asDesired(projection(entranceId, ravi), 0, raviBefore.getFaceSha256()));
        assertThat(deviceReviewItemRepository.findAll()).allSatisfy(item -> assertThat(item.isResolved()).isTrue());
        long applied = readerRevisionRepository.findByDeviceId(readerPk(entranceId)).orElseThrow().getAppliedRevision();
        assertThat(applied).isEqualTo(
                readerRevisionRepository.findByDeviceId(readerPk(entranceId)).orElseThrow().getDesiredRevision());
    }

    private Member onBothReaders(String firstName, String code, String serial) throws Exception {
        Member member = onlyOn(entranceId, firstName, "Shah", code, serial);
        desiredProjectionService.write(member, deviceRepository.findByPublicId(exitId).orElseThrow(),
                memberFaceRepository.findByMemberId(member.getId()).orElseThrow());
        return member;
    }

    private AttendanceEvent eventOn(String readerPublicId) {
        Long reader = readerPk(readerPublicId);
        return attendanceEventRepository.findAll().stream()
                .filter(event -> reader.equals(event.getDeviceId()))
                .findFirst()
                .orElseThrow();
    }
}
