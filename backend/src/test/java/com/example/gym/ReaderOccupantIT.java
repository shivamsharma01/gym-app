package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * What the server does when a reader id holds something other than the member it should: someone
 * else entirely is never treated as the member, a change to the member waits for staff, and every
 * write the gateway is offered says what the reader must still show before it is written.
 */
class ReaderOccupantIT extends AbstractReaderIT {

    private static final String FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String TO = "2027-03-31T23:59:59.0000000+05:30";

    private String entrance;
    private String exit;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("occupant");
        entrance = createReader("Entrance");
        exit = createReader("Exit");
    }

    @Test
    void aNewIdExpectsNoOneAndAVerifiedIdExpectsWhatTheReaderReadBack() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-1", "9101");
        JsonNode first = only(pull(entrance, 0));
        assertThat(first.get("expected").get("present").asBoolean()).isFalse();

        ackEverything(entrance);
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());

        JsonNode renamed = only(pull(entrance, first.get("revision").asLong()));
        assertThat(renamed.get("name").asString()).isEqualTo("Asha Menon");
        JsonNode expected = renamed.get("expected");
        assertThat(expected.get("present").asBoolean()).isTrue();
        assertThat(expected.get("name").asString()).isEqualTo("Asha Rao");
        assertThat(expected.get("faceSha256").asString()).isEqualTo(first.get("faceSha256").asString());
        assertThat(expected.get("validFrom").asString()).isEqualTo(first.get("validFrom").asString());
    }

    @Test
    void aDifferentPersonObservedAtAMappedIdOpensAConflictNotASuspension() throws Exception {
        Member asha = onBoth("Asha", "OCC-2", "9102");
        DesiredMemberProjection row = projection(entrance, asha);
        String id = row.getDeviceUserId();

        readerSays(entrance, person(id, "Bob Stranger", 0, FROM, TO, uploadFace(Color.GREEN)));

        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(entrance), id))
                .as("mapped id stays a member conflict until staff create-as-new").isEmpty();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(entrance), asha.getId()))
                .isPresent();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(entrance), asha.getId()))
                .isPresent();
        DeviceReviewItem item = onlyOpenItem();
        assertThat(item.getKind()).isEqualTo(DeviceReviewItem.CONFLICT);
        assertThat(item.getMemberId()).isEqualTo(asha.getId());
        assertThat(item.getReaderName()).isEqualTo("Bob Stranger");
        assertThat(projection(exit, asha).isPresentOnReader()).as("the other reader keeps Asha").isTrue();
        assertThat(memberRepository.findById(asha.getId()).orElseThrow().getFullName()).isEqualTo("Asha Rao");
    }

    @Test
    void keepReaderValuesAdoptsAFullDeviceEditOntoTheSameMember() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-ADOPT", "9115");
        ackEverything(entrance);
        String id = projection(entrance, asha).getDeviceUserId();
        readerSays(entrance, person(id, "Asha Desk", 0, FROM, TO, uploadFace(Color.GREEN)));
        DeviceReviewItem conflict = onlyOpenItem();
        assertThat(conflict.getKind()).isEqualTo(DeviceReviewItem.CONFLICT);

        JsonNode decided = readJson(postJson("/api/v1/reviews/" + conflict.getPublicId() + "/accept-reader", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(decided.get("decision").asString()).isEqualTo("ACCEPT_READER");
        assertThat(memberRepository.findById(asha.getId()).orElseThrow().getFullName()).isEqualTo("Asha Desk");
        DesiredMemberProjection kept = projection(entrance, asha);
        assertThat(kept.getDeviceUserId()).isEqualTo(id);
        assertThat(kept.getReaderName()).isEqualTo("Asha Desk");
    }

    @Test
    void createAsNewReleasesTheMappedIdForANewMember() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-NEW", "9116");
        ackEverything(entrance);
        String id = projection(entrance, asha).getDeviceUserId();
        readerSays(entrance, person(id, "Bob Stranger", 0, FROM, TO, uploadFace(Color.GREEN)));
        DeviceReviewItem conflict = onlyOpenItem();
        long membersBefore = memberRepository.count();

        JsonNode decided = readJson(postJson("/api/v1/reviews/" + conflict.getPublicId() + "/create-as-new", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(decided.get("decision").asString()).isEqualTo("CREATE");
        assertThat(memberRepository.count()).isEqualTo(membersBefore + 1);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(entrance), asha.getId()))
                .isEmpty();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(readerPk(entrance), id)).isPresent();
        assertThat(projection(entrance, memberRepository.findByPublicId(decided.get("chosenState").asString())
                .orElseThrow()).getReaderName()).isEqualTo("Bob Stranger");
    }

    @Test
    void removingAConflictPublishesRemovalOfTheMappedMember() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-5", "9105");
        ackEverything(entrance);
        String id = projection(entrance, asha).getDeviceUserId();
        readerSays(entrance, person(id, "Bob Stranger", 0, FROM, TO, uploadFace(Color.GREEN)));
        DeviceReviewItem item = onlyOpenItem();

        postJson("/api/v1/reviews/" + item.getPublicId() + "/remove", "{}").andExpect(status().isOk());

        DeviceReviewItem decided = deviceReviewItemRepository.findByPublicId(item.getPublicId()).orElseThrow();
        assertThat(decided.getDecision()).isEqualTo("REMOVE");
        assertThat(only(pull(entrance, 0)).get("present").asBoolean()).isFalse();
    }

    @Test
    void aRenameOrPhotoChangeOfTheSameMemberStaysTheirOwnReview() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-6", "9106");
        ackEverything(entrance);
        DesiredMemberProjection row = projection(entrance, asha);

        readerSays(entrance, person(row.getDeviceUserId(), "Asha Desk", 0, row.getValidFrom(), row.getValidTo(),
                row.getFaceSha256()));

        assertThat(onlyOpenItem().getKind()).isEqualTo(DeviceReviewItem.CONFLICT);
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(entrance),
                row.getDeviceUserId())).isEmpty();
        assertThat(projection(entrance, asha).getDeviceUserId()).isEqualTo(row.getDeviceUserId());
    }

    @Test
    void aReportedEditHoldsTheRevisionUntilStaffDecideOnWhatTheySaw() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-7", "9107");
        ackEverything(entrance);
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());
        DesiredMemberProjection row = projection(entrance, asha);

        report(entrance, row.getRevision(), row.getDeviceUserId(), true, "Asha Rao", 1, row.getValidFrom(),
                row.getValidTo(), row.getFaceSha256()).andExpect(status().isOk());

        assertThat(projection(entrance, asha).isHeld()).isTrue();
        assertThat(pull(entrance, 0).get("items")).as("not offered while staff have not decided").isEmpty();
        DeviceReviewItem item = onlyOpenItem();
        assertThat(item.getKind()).isEqualTo(DeviceReviewItem.CONFLICT);
        assertThat(item.getReaderUserStatus()).isEqualTo(1);

        postJson("/api/v1/reviews/" + item.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());

        JsonNode pulled = only(pull(entrance, 0));
        assertThat(pulled.get("name").asString()).isEqualTo("Asha Menon");
        assertThat(pulled.get("expected").get("name").asString()).isEqualTo("Asha Rao");
        assertThat(pulled.get("expected").get("userStatus").asInt()).as("what staff saw").isEqualTo(1);
        ackEverything(entrance);
        assertThat(deviceReviewItemRepository.findByPublicId(item.getPublicId()).orElseThrow().isResolved()).isTrue();
    }

    @Test
    void aReportedStrangerHoldsTheRevisionAsAConflict() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-8", "9108");
        ackEverything(entrance);
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());
        DesiredMemberProjection row = projection(entrance, asha);

        report(entrance, row.getRevision(), row.getDeviceUserId(), true, "Bob Stranger", 0, row.getValidFrom(),
                row.getValidTo(), uploadFace(Color.GREEN)).andExpect(status().isOk());

        assertThat(projection(entrance, asha).isHeld()).isTrue();
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(entrance),
                row.getDeviceUserId())).isEmpty();
        assertThat(onlyOpenItem().getKind()).isEqualTo(DeviceReviewItem.CONFLICT);
    }

    @Test
    void aReportedEmptyIdHoldsTheRevisionAndOpensAnAbsenceReview() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-9", "9109");
        ackEverything(entrance);
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());
        DesiredMemberProjection row = projection(entrance, asha);

        report(entrance, row.getRevision(), row.getDeviceUserId(), false, null, null, null, null, null)
                .andExpect(status().isOk());

        assertThat(projection(entrance, asha).isHeld()).isTrue();
        assertThat(onlyOpenItem().isReaderAbsent()).isTrue();
        assertThat(pull(entrance, 0).get("items")).isEmpty();
    }

    @Test
    void aLinkThatFindsSomeoneElseIsUndoneAndTheEnrollmentReopens() throws Exception {
        trustedRoster(entrance, person("12", "Door", 0, FROM, TO, null));
        Member ria = onlyOn(exit, "Ria", "Shah", "OCC-10", "9110");
        PendingEnrollment enrollment = pendingEnrollmentRepository
                .findByDeviceIdAndDeviceUserId(readerPk(entrance), "12").orElseThrow();
        postJson("/api/v1/reviews/" + enrollment.getPublicId() + "/link",
                "{\"memberId\":\"" + ria.getPublicId() + "\"}").andExpect(status().isOk());
        DesiredMemberProjection linked = projection(entrance, ria);
        assertThat(only(pull(entrance, 0)).get("expected").get("name").asString()).isEqualTo("Door");

        report(entrance, linked.getRevision(), "12", true, "Bob Stranger", 0, linked.getValidFrom(),
                linked.getValidTo(), uploadFace(Color.GREEN)).andExpect(status().isOk());

        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(entrance), ria.getId())).isEmpty();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(entrance), ria.getId()))
                .isEmpty();
        PendingEnrollment reopened = pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow();
        assertThat(reopened.isResolved()).isFalse();
        assertThat(reopened.getDecision()).isNull();
        assertThat(reopened.getVerificationError()).isNotBlank();
        assertThat(deviceObservedUserRepository.findByDeviceIdAndDeviceUserId(readerPk(entrance), "12").orElseThrow()
                .getReaderName()).isEqualTo("Bob Stranger");
        assertThat(projection(exit, ria).isPresentOnReader()).isTrue();
    }

    @Test
    void aRejectThatFindsSomeoneElseIsUndoneAndNothingIsRemoved() throws Exception {
        trustedRoster(entrance, person("13", "Walk In", 0, FROM, TO, null));
        PendingEnrollment enrollment = pendingEnrollmentRepository
                .findByDeviceIdAndDeviceUserId(readerPk(entrance), "13").orElseThrow();
        postJson("/api/v1/reviews/" + enrollment.getPublicId() + "/reject", "{}").andExpect(status().isOk());
        JsonNode absence = only(pull(entrance, 0));
        assertThat(absence.get("present").asBoolean()).isFalse();
        assertThat(absence.get("expected").get("name").asString()).isEqualTo("Walk In");

        report(entrance, absence.get("revision").asLong(), "13", true, "Bob Stranger", 0, null, null, null)
                .andExpect(status().isOk());

        assertThat(pull(entrance, 0).get("items")).isEmpty();
        PendingEnrollment reopened = pendingEnrollmentRepository.findByPublicId(enrollment.getPublicId()).orElseThrow();
        assertThat(reopened.isResolved()).isFalse();
        assertThat(reopened.getDecision()).isNull();
    }

    @Test
    void aReportForARevisionThatWasReplacedIsRefused() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "OCC-12", "9112");
        long first = projection(entrance, asha).getRevision();
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());

        report(entrance, first, projection(entrance, asha).getDeviceUserId(), false, null, null, null, null, null)
                .andExpect(status().isConflict());

        assertThat(projection(entrance, asha).isHeld()).isFalse();
    }

    @Test
    void aResolvedConflictOnOneReaderDoesNotHideALaterConflictOnAnother() throws Exception {
        Member asha = onBoth("Asha", "OCC-13", "9113");
        readerSays(entrance, renamed(projection(entrance, asha), "Asha Edited A"));
        DeviceReviewItem first = onlyOpenItem();
        postJson("/api/v1/reviews/" + first.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());
        ackEverything(entrance);
        assertThat(openItems()).isEmpty();

        readerSays(exit, renamed(projection(exit, asha), "Asha Edited B"));

        DeviceReviewItem second = onlyOpenItem();
        assertThat(second.getDeviceId()).isEqualTo(readerPk(exit));
        assertThat(second.getReaderName()).isEqualTo("Asha Edited B");
    }

    @Test
    void aDecisionOnOneReadersConflictActsOnThatReaderAndLeavesTheOtherOpen() throws Exception {
        Member asha = onBoth("Asha", "OCC-14", "9114");
        long exitBefore = projection(exit, asha).getRevision();
        readerSays(entrance, renamed(projection(entrance, asha), "Asha Edited A"));
        readerSays(exit, renamed(projection(exit, asha), "Asha Edited B"));
        List<DeviceReviewItem> items = openItems();
        assertThat(items).extracting(DeviceReviewItem::getDeviceId)
                .containsExactlyInAnyOrder(readerPk(entrance), readerPk(exit));
        DeviceReviewItem onExit = items.stream().filter(i -> i.getDeviceId().equals(readerPk(exit))).findFirst()
                .orElseThrow();

        postJson("/api/v1/reviews/" + onExit.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());

        assertThat(projection(exit, asha).getRevision()).isGreaterThan(exitBefore);
        assertThat(openItems()).filteredOn(i -> i.getDecision() == null)
                .extracting(DeviceReviewItem::getDeviceId).containsExactly(readerPk(entrance));
    }

    private Member onBoth(String first, String code, String serial) throws Exception {
        Member member = onlyOn(entrance, first, "Rao", code, serial);
        desiredProjectionService.write(member, deviceRepository.findByPublicId(exit).orElseThrow(),
                memberFaceService.find(member.getId()).orElseThrow());
        ackEverything(entrance);
        ackEverything(exit);
        return member;
    }

    private static String renamed(DesiredMemberProjection row, String name) {
        return person(row.getDeviceUserId(), name, row.getUserStatus(), row.getValidFrom(), row.getValidTo(),
                row.getFaceSha256());
    }

    private List<DeviceReviewItem> openItems() {
        return deviceReviewItemRepository.findByTenantIdAndResolvedFalse(tenant.getId());
    }

    private DeviceReviewItem onlyOpenItem() {
        List<DeviceReviewItem> open = openItems();
        assertThat(open).hasSize(1);
        return open.getFirst();
    }

    private static JsonNode only(JsonNode page) {
        assertThat(page.get("items")).hasSize(1);
        return page.get("items").get(0);
    }

    private long cursor(String reader) {
        return readerRevisionRepository.findByDeviceId(readerPk(reader)).orElseThrow().getDesiredRevision();
    }

    private ResultActions report(String reader, long revision, String deviceUserId, boolean present, String name,
                                 Integer userStatus, String validFrom, String validTo, String faceSha256)
            throws Exception {
        String body = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","present":%s,"name":%s,"nameEx":null,\
                "userStatus":%s,"validFrom":%s,"validTo":%s,"authority":"Customer","doorNum":1,"timeSectionNum":1,\
                "faceSha256":%s}""".formatted(reader, revision, deviceUserId, present, quoted(name),
                userStatus == null ? "null" : userStatus.toString(), quoted(validFrom), quoted(validTo),
                quoted(faceSha256));
        return mockMvc.perform(post("/internal/gateway/desired/occupant")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
