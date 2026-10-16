package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.member.Member;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Roster replication: two reader ids with the same name are two members when staff choose
 * DISTINCT_SOURCE. The same photo still holds the second person for Review. Default import stays
 * conservative.
 */
class ReaderImportDistinctIT extends AbstractReaderIT {

    private static final String FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String TO = "2027-03-31T23:59:59.0000000+05:30";

    private String readerId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("import-distinct");
        readerId = createReader("Entrance");
    }

    @Test
    void distinctSourceCreatesTwoMembersWithTheSameNameAndDifferentPhotos() throws Exception {
        trustedRoster(readerId,
                person("11", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)),
                person("12", "Asha Rao", 0, FROM, TO, uploadFace(Color.BLUE)));

        JsonNode conservative = importMode("CONSERVATIVE");
        assertThat(conservative.get("created").asInt()).isEqualTo(1);
        assertThat(conservative.get("skipped")).hasSize(1);
        assertThat(conservative.get("summary").get("possibleNameMatch").asInt()).isEqualTo(1);
        assertThat(memberRepository.count()).isEqualTo(1);

        JsonNode distinct = importMode("DISTINCT_SOURCE");
        assertThat(distinct.get("created").asInt()).isEqualTo(1);
        assertThat(distinct.get("skipped")).singleElement()
                .satisfies(row -> assertThat(row.get("deviceUserId").asString()).isEqualTo("11"));
        assertThat(memberRepository.count()).isEqualTo(2);
        assertThat(memberRepository.findAll().stream().map(Member::getFullName).toList())
                .containsOnly("Asha Rao", "Asha Rao");
    }

    @Test
    void distinctSourceStillHoldsASecondIdWithTheSamePhoto() throws Exception {
        String face = uploadFace(Color.RED);
        trustedRoster(readerId,
                person("11", "Asha Rao", 0, FROM, TO, face),
                person("12", "Ravi Iyer", 0, FROM, TO, face));

        JsonNode first = importMode("DISTINCT_SOURCE");
        assertThat(first.get("created").asInt()).isEqualTo(1);
        assertThat(first.get("skipped")).hasSize(1);
        assertThat(first.get("summary").get("possibleFaceMatch").asInt()).isEqualTo(1);

        JsonNode again = importMode("DISTINCT_SOURCE");
        assertThat(again.get("created").asInt()).isZero();
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    void aRepeatedDistinctImportIsIdempotent() throws Exception {
        trustedRoster(readerId, person("11", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)));
        assertThat(importMode("DISTINCT_SOURCE").get("created").asInt()).isEqualTo(1);
        assertThat(importMode("DISTINCT_SOURCE").get("created").asInt()).isZero();
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    private JsonNode importMode(String mode) throws Exception {
        return readJson(postJson("/api/v1/devices/" + readerId + "/import-pending?mode=" + mode, "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
