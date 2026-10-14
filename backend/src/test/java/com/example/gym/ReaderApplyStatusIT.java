package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractReaderIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * What the gateway learns before and after it applies desired state: whether the server has
 * processed its reader observation, that a revision it could not apply is visible and stays
 * desired, and that a member counts as applied on a reader only when that row was read back.
 */
class ReaderApplyStatusIT extends AbstractReaderIT {

    private String entrance;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("apply");
        entrance = createReader("Entrance");
    }

    @Test
    void anObservationIsProcessedOnlyOnceEveryMessageWasHandled() throws Exception {
        String sent = UUID.randomUUID().toString();
        String notYet = UUID.randomUUID().toString();

        assertThat(observed(gatewayToken, entrance, sent)).isFalse();
        say(sent, entrance, "{\"announcedTotal\":0,\"users\":[]}");

        assertThat(observed(gatewayToken, entrance, sent)).isTrue();
        assertThat(observed(gatewayToken, entrance, sent, notYet)).as("one message still outstanding").isFalse();
    }

    @Test
    void anotherGatewayCannotUseThisGatewaysMessagesOrReaders() throws Exception {
        String sent = UUID.randomUUID().toString();
        say(sent, entrance, "{\"announcedTotal\":0,\"users\":[]}");
        var otherGym = createTenant("Other Gym", "apply-other");
        createUser(otherGym.getId(), "apply-other-admin", "apply-other-admin@gym.local", "GYM_ADMIN");
        String otherAdmin = tokenFor("apply-other-admin");
        String[] other = enrolGateway(otherAdmin);
        String backReader = readJson(postAs(otherAdmin, "/api/v1/devices",
                "{\"name\":\"Back\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.21\",\"port\":37777,"
                        + "\"gatewayId\":\"" + other[0] + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();

        assertThat(observed(other[1], backReader, sent)).isFalse();
        observedRequest(other[1], entrance, sent).andExpect(status().isNotFound());
    }

    @Test
    void aFailedRevisionIsShownStaysDesiredAndClearsWhenItIsReadBack() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-1", "9201");
        DesiredMemberProjection row = projection(entrance, asha);

        failed(entrance, row.getRevision(), row.getDeviceUserId(), "create failed", 6, false)
                .andExpect(status().isOk());

        JsonNode reader = readerRow(asha);
        assertThat(reader.get("applied").asBoolean()).isFalse();
        assertThat(reader.get("applyError").asString()).isEqualTo("create failed");
        assertThat(reader.get("applyAttempts").asInt()).isEqualTo(6);
        assertThat(reader.get("applyErrorPermanent").asBoolean()).isFalse();
        assertThat(pull(entrance, 0).get("items")).as("still desired").hasSize(1);

        ackEverything(entrance);

        reader = readerRow(asha);
        assertThat(reader.get("applied").asBoolean()).isTrue();
        assertThat(reader.get("applyError").isNull()).isTrue();
    }

    @Test
    void aFailureForARevisionThatWasReplacedIsRefusedAndNotShown() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-2", "9202");
        DesiredMemberProjection first = projection(entrance, asha);
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());

        failed(entrance, first.getRevision(), first.getDeviceUserId(), "create failed", 6, false)
                .andExpect(status().isConflict());

        assertThat(readerRow(asha).get("applyError").isNull()).isTrue();
    }

    @Test
    void anOldFailureIsNotShownAgainstANewerRevision() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-3", "9203");
        DesiredMemberProjection first = projection(entrance, asha);
        failed(entrance, first.getRevision(), first.getDeviceUserId(), "face is missing", 1, true)
                .andExpect(status().isOk());
        assertThat(readerRow(asha).get("applyErrorPermanent").asBoolean()).isTrue();

        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());

        JsonNode reader = readerRow(asha);
        assertThat(reader.get("applyError").isNull()).isTrue();
        assertThat(reader.get("applyErrorPermanent").asBoolean()).isFalse();
    }

    @Test
    void aMemberIsAppliedOnlyWhenTheirOwnRowWasReadBackNotWhenALaterRevisionWas() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-4", "9204");
        Member ravi = onlyOn(entrance, "Ravi", "Iyer", "APL-5", "9205");
        DesiredMemberProjection raviRow = projection(entrance, ravi);
        JsonNode raviItem = pull(entrance, raviRow.getRevision() - 1).get("items").get(0);

        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readBack(raviItem)))
                .andExpect(status().isOk());

        assertThat(readerRow(ravi).get("applied").asBoolean()).isTrue();
        assertThat(readerRow(asha).get("applied").asBoolean())
                .as("the reader's highest acknowledged revision is past Asha's, but Asha was not read back")
                .isFalse();
        assertThat(readerRow(asha).get("appliedRevision").asLong()).isEqualTo(raviRow.getRevision());
    }

    @Test
    void anIdWhoseRemovalWasNotReadBackIsNotGivenToANewMemberAfterALaterAck() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-6", "9206");
        ackEverything(entrance);
        String ashaId = projection(entrance, asha).getDeviceUserId();
        postJson("/api/v1/members/" + asha.getPublicId() + "/device-sync/" + entrance + "/remove", "{}")
                .andExpect(status().is2xxSuccessful());
        Member ravi = onlyOn(entrance, "Ravi", "Iyer", "APL-7", "9207");
        DesiredMemberProjection raviRow = projection(entrance, ravi);
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readBack(pull(entrance, raviRow.getRevision() - 1).get("items").get(0))))
                .andExpect(status().isOk());

        Member cara = onlyOn(entrance, "Cara", "Das", "APL-8", "9208");

        assertThat(projection(entrance, cara).getDeviceUserId()).isNotEqualTo(ashaId);
    }

    @Test
    void aHeldRowStaysHeldWhenTheMemberIsEditedOrSentAgain() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-9", "9209");
        ackEverything(entrance);
        DesiredMemberProjection row = projection(entrance, asha);
        readerSays(entrance, person(row.getDeviceUserId(), "Asha Desk", 0, row.getValidFrom(), row.getValidTo(),
                row.getFaceSha256()));
        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Menon\"}")
                .andExpect(status().isOk());
        row = projection(entrance, asha);
        occupant(entrance, row.getRevision(), row.getDeviceUserId(), "Asha Desk", row.getValidFrom(), row.getValidTo(),
                row.getFaceSha256()).andExpect(status().isOk());
        assertThat(projection(entrance, asha).isHeld()).isTrue();

        putJson("/api/v1/members/" + asha.getPublicId(), "{\"firstName\":\"Asha\",\"lastName\":\"Iyer\"}")
                .andExpect(status().isOk());
        postJson("/api/v1/members/" + asha.getPublicId() + "/device-sync/" + entrance + "/retry", "{}")
                .andExpect(status().is2xxSuccessful());

        DesiredMemberProjection after = projection(entrance, asha);
        assertThat(after.isHeld()).isTrue();
        assertThat(after.getExpected()).as("neither edit set what the reader must show").isNull();
        assertThat(pull(entrance, 0).get("items")).as("nothing is offered until staff decide").isEmpty();
        assertThat(readerRow(asha).get("held").asBoolean()).isTrue();
    }

    @Test
    void aReaderChangeThatOvertakesADecisionLetsStaffDecideAgain() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-10", "9210");
        ackEverything(entrance);
        DesiredMemberProjection row = projection(entrance, asha);
        readerSays(entrance, person(row.getDeviceUserId(), "Asha Desk", 0, row.getValidFrom(), row.getValidTo(),
                row.getFaceSha256()));
        DeviceReviewItem item = onlyOpenItem();
        postJson("/api/v1/reviews/" + item.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());
        DesiredMemberProjection decided = projection(entrance, asha);

        occupant(entrance, decided.getRevision(), decided.getDeviceUserId(), "Asha Front", decided.getValidFrom(),
                decided.getValidTo(), row.getFaceSha256()).andExpect(status().isOk());

        DeviceReviewItem overtaken = deviceReviewItemRepository.findByPublicId(item.getPublicId()).orElseThrow();
        assertThat(overtaken.isResolved()).isFalse();
        assertThat(overtaken.getVerificationError()).contains("changed before the decision");
        assertThat(overtaken.getReaderName()).isEqualTo("Asha Front");
        assertThat(projection(entrance, asha).isHeld()).isTrue();
        assertThat(pull(entrance, 0).get("items")).isEmpty();

        postJson("/api/v1/reviews/" + item.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());

        JsonNode again = pull(entrance, 0).get("items").get(0);
        assertThat(again.get("expected").get("name").asString()).isEqualTo("Asha Front");
    }

    @Test
    void seeingWhatStaffSawAgainDoesNotUndoTheirDecision() throws Exception {
        Member asha = onlyOn(entrance, "Asha", "Rao", "APL-11", "9211");
        ackEverything(entrance);
        DesiredMemberProjection row = projection(entrance, asha);
        String edited = person(row.getDeviceUserId(), "Asha Desk", 0, row.getValidFrom(), row.getValidTo(),
                row.getFaceSha256());
        String first = UUID.randomUUID().toString();
        say(first, entrance, edited);
        DeviceReviewItem item = onlyOpenItem();
        postJson("/api/v1/reviews/" + item.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());
        long decidedRevision = projection(entrance, asha).getRevision();

        say(first, entrance, edited);
        say(UUID.randomUUID().toString(), entrance, edited);
        say(UUID.randomUUID().toString(), entrance, "{\"announcedTotal\":1,\"users\":[" + edited + "]}");

        List<DeviceReviewItem> open = openItems();
        assertThat(open).hasSize(1);
        DeviceReviewItem still = open.getFirst();
        assertThat(still.getPublicId()).isEqualTo(item.getPublicId());
        assertThat(still.getDecision()).isEqualTo("ACCEPT_SERVER");
        assertThat(still.getVerificationError()).isNull();
        DesiredMemberProjection after = projection(entrance, asha);
        assertThat(after.isHeld()).isFalse();
        assertThat(after.getRevision()).isEqualTo(decidedRevision);
        assertThat(pull(entrance, 0).get("items")).hasSize(1);
    }

    private JsonNode readerRow(Member member) throws Exception {
        return getJson("/api/v1/members/" + member.getPublicId() + "/device-sync").get("devices").get(0);
    }

    private List<DeviceReviewItem> openItems() {
        return deviceReviewItemRepository.findByTenantIdAndResolvedFalse(tenant.getId());
    }

    private DeviceReviewItem onlyOpenItem() {
        List<DeviceReviewItem> open = openItems();
        assertThat(open).hasSize(1);
        return open.getFirst();
    }

    private void say(String messageId, String reader, String payload) throws Exception {
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(messageId, Instant.now(), gatewayPublicId, reader, UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private boolean observed(String bearer, String reader, String... messageIds) throws Exception {
        return readJson(observedRequest(bearer, reader, messageIds).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("processed").asBoolean();
    }

    private ResultActions observedRequest(String bearer, String reader, String... messageIds) throws Exception {
        String ids = String.join("\",\"", messageIds);
        return mockMvc.perform(post("/internal/gateway/desired/observed")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + reader + "\",\"messageIds\":[\"" + ids + "\"]}"));
    }

    private ResultActions failed(String reader, long revision, String deviceUserId, String error, int attempts,
                                 boolean permanent) throws Exception {
        return mockMvc.perform(post("/internal/gateway/desired/failed")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"deviceId":"%s","revision":%d,"deviceUserId":"%s","error":"%s","attempts":%d,"permanent":%s}
                        """.formatted(reader, revision, deviceUserId, error, attempts, permanent)));
    }

    private ResultActions occupant(String reader, long revision, String deviceUserId, String name, String validFrom,
                                   String validTo, String faceSha256) throws Exception {
        String body = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","present":true,"name":"%s","nameEx":null,\
                "userStatus":0,"validFrom":"%s","validTo":"%s","authority":"Customer","doorNum":1,"timeSectionNum":1,\
                "faceSha256":"%s"}""".formatted(reader, revision, deviceUserId, name, validFrom, validTo, faceSha256);
        return mockMvc.perform(post("/internal/gateway/desired/occupant")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String readBack(JsonNode item) {
        String nameEx = item.get("nameEx").isNull() ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        return """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,"userStatus":%d,\
                "validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(entrance, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                item.get("name").asString(), nameEx, item.get("userStatus").asInt(), item.get("validFrom").asString(),
                item.get("validTo").asString(), item.get("faceSha256").asString());
    }

    /** The public id and credential of a gateway enrolled by that gym's admin. */
    private String[] enrolGateway(String adminToken) throws Exception {
        JsonNode gateway = readJson(postAs(adminToken, "/api/v1/gateways", "{\"name\":\"Back office\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String publicId = gateway.get("id").asString();
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + publicId + "\",\"enrollmentToken\":\""
                                + gateway.get("token").asString() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new String[] {publicId, readJson(enrolled).get("credential").asString()};
    }
}
