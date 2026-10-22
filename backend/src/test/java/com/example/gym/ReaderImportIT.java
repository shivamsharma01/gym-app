package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The first connection to an empty server: the readers already hold people. Staff import one
 * reader at a time. Each member keeps what the reader shows, so nothing is written back, and a
 * person on a second reader is linked by staff, never copied there a second time.
 */
class ReaderImportIT extends AbstractReaderIT {

    /** As the gateway formats a reader time: round-trip with seven fraction digits. */
    private static final String ASHA_FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String ASHA_TO = "2027-03-31T23:59:59.0000000+05:30";
    private static final String RAVI_FROM = "2026-02-01T00:00:00.0000000+05:30";
    private static final String RAVI_TO = "2026-12-31T23:59:59.0000000+05:30";

    private String entranceId;
    private String exitId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("import");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
    }

    @Test
    void importingAReaderCreatesOneMemberPerPersonHoldingExactlyWhatTheReaderShows() throws Exception {
        String ashaFace = uploadFace(Color.RED);
        String raviFace = uploadFace(Color.BLUE);
        trustedRoster(entranceId,
                person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, ashaFace),
                person("12", "Ravi Iyer", 1, RAVI_FROM, RAVI_TO, raviFace),
                person("13", "No Photo", 0, ASHA_FROM, ASHA_TO, null));
        assertThat(memberRepository.count()).isZero();

        JsonNode imported = importPending(entranceId);

        assertThat(imported.get("created").asInt()).isEqualTo(2);
        assertThat(imported.get("skipped")).singleElement()
                .satisfies(row -> assertThat(row.get("deviceUserId").asString()).isEqualTo("13"));
        assertThat(memberRepository.count()).isEqualTo(2);
        assertThat(membershipRepository.count()).isZero();

        Member asha = member("Asha Rao");
        DesiredMemberProjection ashaRow = projection(entranceId, asha);
        assertThat(ashaRow.getDeviceUserId()).isEqualTo("11");
        assertThat(ashaRow.getReaderName()).isEqualTo("Asha Rao");
        assertThat(ashaRow.getUserStatus()).isZero();
        assertThat(ashaRow.getValidFrom()).isEqualTo("2026-01-01T00:00:00+05:30");
        assertThat(ashaRow.getValidTo()).isEqualTo("2027-03-31T23:59:59+05:30");
        assertThat(ashaRow.getFaceSha256()).isEqualTo(ashaFace);
        assertThat(asha.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        Member ravi = member("Ravi Iyer");
        assertThat(ravi.getStatus()).as("disabled on the reader is an inactive member").isEqualTo(MemberStatus.INACTIVE);
        DesiredMemberProjection raviRow = projection(entranceId, ravi);
        assertThat(raviRow.getDeviceUserId()).isEqualTo("12");
        assertThat(raviRow.getUserStatus()).as("a person disabled on the reader stays disabled").isEqualTo(1);
        assertThat(raviRow.getValidFrom()).isEqualTo("2026-02-01T00:00:00+05:30");
        assertThat(raviRow.getValidTo()).isEqualTo("2026-12-31T23:59:59+05:30");

        for (JsonNode item : pull(entranceId, 0).get("items")) {
            assertThat(item.get("authority").asString()).isEqualTo("Customer");
            assertThat(item.get("doorNum").asInt()).isEqualTo(1);
            assertThat(item.get("timeSectionNum").asInt()).isEqualTo(1);
            assertThat(item.get("expected").get("present").asBoolean())
                    .as("the reader person is the only record the gateway may find at the id").isTrue();
            assertThat(item.get("expected").get("name").asString()).isEqualTo(item.get("name").asString());
        }
        ackEverything(entranceId);
        assertThat(enrollment(entranceId, "11").isResolved()).isTrue();
        assertThat(enrollment(entranceId, "12").isResolved()).isTrue();
        assertThat(enrollment(entranceId, "13").isResolved()).isFalse();
        assertThat(enrollment(entranceId, "13").getDecision()).isNull();
        assertThat(desiredMemberProjectionRepository.findByDeviceId(readerPk(exitId))).isEmpty();
        assertThat(deviceSyncCommandRepository.count()).isZero();
        assertThat(deviceReviewItemRepository.count()).isZero();
    }

    @Test
    void aPersonOnBothReadersIsImportedOnceAndLinkedOnTheSecondReaderUnderItsOwnId() throws Exception {
        String entranceFace = uploadFace(Color.RED);
        String exitFace = uploadFace(Color.GREEN);
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, entranceFace));
        trustedRoster(exitId, person("7", "Asha Rao", 0, RAVI_FROM, RAVI_TO, exitFace));

        assertThat(importPending(entranceId).get("created").asInt()).isEqualTo(1);
        ackEverything(entranceId);
        Member asha = member("Asha Rao");

        trustedRoster(exitId, person("7", "Asha Rao", 0, RAVI_FROM, RAVI_TO, exitFace));
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), asha.getId()))
                .as("not copied to the exit under a second id while id 7 is undecided")
                .isEmpty();
        assertThat(memberDeviceMappingRepository.findByMemberId(asha.getId())).hasSize(1);

        JsonNode report = readJson(postJson("/api/v1/reviews/bootstrap", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.get("rows")).anySatisfy(row -> {
            assertThat(row.get("outcome").asString()).isEqualTo("UNLINKED");
            assertThat(row.get("deviceUserId").asString()).isEqualTo("7");
            assertThat(row.get("suggestionMemberId").asString()).isEqualTo(asha.getPublicId());
        });

        postJson("/api/v1/reviews/" + enrollment(exitId, "7").getPublicId() + "/link",
                "{\"memberId\":\"" + asha.getPublicId() + "\"}").andExpect(status().isOk());

        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(memberDeviceMappingRepository.findByMemberId(asha.getId()))
                .extracting(mapping -> mapping.getDeviceUserId())
                .containsExactlyInAnyOrder("11", "7");
        DesiredMemberProjection onExit = projection(exitId, asha);
        assertThat(onExit.getDeviceUserId()).isEqualTo("7");
        assertThat(onExit.getValidFrom()).as("link keeps the exit reader's window, not the entrance import")
                .isEqualTo("2026-02-01T00:00:00+05:30");
        assertThat(onExit.getValidTo()).isEqualTo("2026-12-31T23:59:59+05:30");
        assertThat(onExit.getUserStatus()).isZero();
        ackEverything(exitId);
        assertThat(enrollment(exitId, "7").isResolved()).isTrue();

        trustedRoster(exitId, asDesired(onExit, 0, onExit.getFaceSha256()));
        assertThat(memberDeviceMappingRepository.findByMemberId(asha.getId())).hasSize(2);
        assertThat(desiredMemberProjectionRepository.findByDeviceId(readerPk(exitId))).hasSize(1);
        assertThat(deviceReviewItemRepository.count()).isZero();
    }

    @Test
    void anAdminEditWritesTheChangeAndKeepsTheReaderWindowUntilAMembershipExists() throws Exception {
        trustedRoster(entranceId,
                person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, uploadFace(Color.RED)),
                person("12", "Ravi Iyer", 1, RAVI_FROM, RAVI_TO, uploadFace(Color.BLUE)));
        importPending(entranceId);
        ackEverything(entranceId);
        Member asha = member("Asha Rao");
        Member ravi = member("Ravi Iyer");
        long ashaRevision = projection(entranceId, asha).getRevision();

        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());
        putJson("/api/v1/members/" + ravi.getPublicId(), "{\"firstName\":\"Ravi\",\"lastName\":\"Kumar\"}")
                .andExpect(status().isOk());

        DesiredMemberProjection renamed = projection(entranceId, asha);
        assertThat(renamed.getRevision()).isGreaterThan(ashaRevision);
        assertThat(renamed.getReaderName()).isEqualTo("Asha Menon");
        assertThat(renamed.getValidFrom()).isEqualTo("2026-01-01T00:00:00+05:30");
        assertThat(renamed.getValidTo()).isEqualTo("2027-03-31T23:59:59+05:30");
        assertThat(renamed.getUserStatus()).isZero();
        DesiredMemberProjection stillDisabled = projection(entranceId, ravi);
        assertThat(stillDisabled.getReaderName()).isEqualTo("Ravi Kumar");
        assertThat(stillDisabled.getUserStatus()).as("an edit does not enable a person the reader disabled")
                .isEqualTo(1);

        paidMembership(memberRepository.findById(asha.getId()).orElseThrow());
        DesiredMemberProjection withPlan = projection(entranceId, asha);
        assertThat(withPlan.getValidFrom()).startsWith(LocalDate.now().minusDays(5).toString());
        assertThat(withPlan.getValidTo()).startsWith(LocalDate.now().plusDays(25).toString());
        assertThat(withPlan.getUserStatus()).isZero();
    }

    @Test
    void anImportedMemberReachesAnotherReaderOnlyAfterStaffDecideOnThePeopleThatReaderShowed() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, uploadFace(Color.RED)));
        importPending(entranceId);
        ackEverything(entranceId);
        Member asha = member("Asha Rao");
        Member om = onlyOn(entranceId, "Om", "Rao", "IMPORT-OM", "7101");
        ackEverything(entranceId);

        trustedRoster(exitId, person("7", "Stranger", 0, RAVI_FROM, RAVI_TO, uploadFace(Color.GREEN)));

        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), asha.getId()))
                .as("the stranger may be Asha; she waits for the staff decision")
                .isEmpty();
        DesiredMemberProjection omOnExit = projection(exitId, om);
        assertThat(omOnExit.getDeviceUserId()).as("a server-created member is still seeded").isNotEqualTo("7");

        postJson("/api/v1/reviews/" + enrollment(exitId, "7").getPublicId() + "/reject", "{}")
                .andExpect(status().isOk());
        ackEverything(exitId);
        assertThat(enrollment(exitId, "7").isResolved()).isTrue();
        trustedRoster(exitId, asDesired(omOnExit, 0, omOnExit.getFaceSha256()));

        DesiredMemberProjection seeded = projection(exitId, asha);
        assertThat(seeded.isPresentOnReader()).isTrue();
        assertThat(seeded.getDeviceUserId()).isNotIn("7", "11", omOnExit.getDeviceUserId());
        assertThat(seeded.getValidFrom()).isEqualTo(projection(entranceId, asha).getValidFrom());
        assertThat(seeded.getValidTo()).isEqualTo(projection(entranceId, asha).getValidTo());
    }

    @Test
    void onlyAGymAdminMayImportAReader() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, uploadFace(Color.RED)));
        createUser(tenant.getId(), "front-desk", "front-desk@gym.local", "STAFF");

        postAs(tokenFor("front-desk"), "/api/v1/devices/" + entranceId + "/import-pending", "{}")
                .andExpect(status().isForbidden());

        assertThat(memberRepository.count()).isZero();
        assertThat(enrollment(entranceId, "11").getDecision()).isNull();
    }

    private JsonNode importPending(String readerPublicId) throws Exception {
        return readJson(postJson("/api/v1/devices/" + readerPublicId + "/import-pending", "{}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private PendingEnrollment enrollment(String readerPublicId, String deviceUserId) {
        return pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(readerPublicId), deviceUserId)
                .orElseThrow();
    }

    private Member member(String fullName) {
        return memberRepository.findAll().stream()
                .filter(member -> fullName.equals(member.getFullName()))
                .findFirst()
                .orElseThrow();
    }
}
