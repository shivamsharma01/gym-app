package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Linking a second reader's people in one go: only people who are, beyond reasonable doubt, a
 * member another reader holds are offered, and only the pairs staff confirmed that still match
 * are linked.
 */
class ReaderLinkMatchesIT extends AbstractReaderIT {

    private static final String FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String TO = "2027-03-31T23:59:59.0000000+05:30";
    private static final String OTHER_TO = "2026-12-31T23:59:59.0000000+05:30";

    private String entranceId;
    private String exitId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("link-matches");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
    }

    @Test
    void theSamePersonOnBothReadersIsOfferedAndLinkedUnderTheSecondReadersId() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)));
        importPending(entranceId);
        trustedRoster(exitId, person("7", " asha  rao", 0, FROM, TO, uploadFace(Color.GREEN)));
        Member asha = member("Asha Rao");

        JsonNode offered = matches(exitId);
        assertThat(offered).singleElement().satisfies(row -> {
            assertThat(row.get("deviceUserId").asString()).isEqualTo("7");
            assertThat(row.get("memberId").asString()).isEqualTo(asha.getPublicId());
            assertThat(row.get("otherReaderName").asString()).isEqualTo("Entrance");
        });

        JsonNode done = confirm(exitId, offered.get(0).get("enrollmentId").asString(), asha.getPublicId());

        assertThat(done.get("linked").asInt()).isEqualTo(1);
        assertThat(done.get("notLinked")).isEmpty();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), asha.getId()))
                .map(MemberDeviceMapping::getDeviceUserId).contains("7");
        assertThat(enrollment(exitId, "7").getDecision()).isEqualTo("LINK");
        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(matches(exitId)).isEmpty();
    }

    @Test
    void differentDatesOrAnotherEnabledStateIsNotOffered() throws Exception {
        trustedRoster(entranceId,
                person("11", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)),
                person("12", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.BLUE)));
        importPending(entranceId);
        trustedRoster(exitId,
                person("7", "Asha Rao", 0, FROM, OTHER_TO, uploadFace(Color.GREEN)),
                person("8", "Ravi Iyer", 1, FROM, TO, uploadFace(Color.ORANGE)));

        assertThat(matches(exitId)).isEmpty();
    }

    @Test
    void aNameShownTwiceOnEitherSideIsNotOffered() throws Exception {
        trustedRoster(entranceId,
                person("11", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)),
                person("12", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.BLUE)));
        importPending(entranceId);
        saveMember(tenant.getId(), "ID-RAVI2", "Ravi", "Iyer", "7301");
        trustedRoster(exitId,
                person("7", "Asha Rao", 0, FROM, TO, uploadFace(Color.GREEN)),
                person("8", "Asha Rao", 0, FROM, TO, uploadFace(Color.ORANGE)),
                person("9", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.MAGENTA)));

        assertThat(matches(exitId)).as("two Ashas on the exit, two Ravi members").isEmpty();
    }

    @Test
    void aPairThatNoLongerMatchesTheConfirmedMemberIsLeftForTheReviewPage() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)));
        importPending(entranceId);
        trustedRoster(exitId, person("7", "Asha Rao", 0, FROM, TO, uploadFace(Color.GREEN)));
        Member other = saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101");
        PendingEnrollment seven = enrollment(exitId, "7");

        JsonNode done = confirm(exitId, seven.getPublicId(), other.getPublicId());

        assertThat(done.get("linked").asInt()).isZero();
        assertThat(done.get("notLinked")).singleElement()
                .satisfies(id -> assertThat(id.asString()).isEqualTo(seven.getPublicId()));
        assertThat(enrollment(exitId, "7").getDecision()).isNull();
        assertThat(memberDeviceMappingRepository.findByDeviceId(readerPk(exitId))).isEmpty();
    }

    private JsonNode importPending(String readerPublicId) throws Exception {
        return readJson(postJson("/api/v1/devices/" + readerPublicId + "/import-pending", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode matches(String readerPublicId) throws Exception {
        return getJson("/api/v1/devices/" + readerPublicId + "/link-matches");
    }

    private JsonNode confirm(String readerPublicId, String enrollmentId, String memberId) throws Exception {
        String body = "{\"links\":[{\"enrollmentId\":\"" + enrollmentId + "\",\"memberId\":\"" + memberId + "\"}]}";
        return readJson(postJson("/api/v1/devices/" + readerPublicId + "/link-matches", body)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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
