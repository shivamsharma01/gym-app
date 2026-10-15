package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DeviceAuthorizationService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What a reader holds for a member, by membership history:
 * <ul>
 *   <li>Never had a membership (first import from a reader): enabled, with the dates the reader
 *       showed, on every reader.</li>
 *   <li>Every membership cancelled: disabled, with the latest cancelled membership's dates.</li>
 *   <li>A cancelled membership beside a usable one: the usable one decides.</li>
 * </ul>
 */
class MembershipAccessRuleIT extends AbstractReaderIT {

    private static final String READER_FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String READER_TO = "2027-03-31T23:59:59.0000000+05:30";
    private static final int ENABLED = 0;
    private static final int DISABLED = 1;

    @Autowired
    private DeviceAuthorizationService authorization;

    private String entranceId;
    private String exitId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("access-rule");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
        trustedRoster(exitId);
    }

    /**
     * First installation: the reader holds an active person whose reader dates enclose today, and
     * the server has no membership for them. Importing creates an active member who keeps those
     * dates and stays enabled on that reader and on every other reader.
     */
    @Test
    void aReaderPersonImportedWithoutAMembershipKeepsTheirReaderDatesAndAccessOnEveryReader() throws Exception {
        Member asha = importAsha();

        assertThat(asha.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(asha.getAdoptedValidFrom()).isEqualTo("2026-01-01T00:00:00+05:30");
        assertThat(asha.getAdoptedValidTo()).isEqualTo("2027-03-31T23:59:59+05:30");
        assertThat(authorization.window(asha)).as("no membership, no window").isEmpty();
        assertThat(projection(entranceId, asha).getUserStatus()).isEqualTo(ENABLED);

        trustedRoster(exitId);

        DesiredMemberProjection onExit = projection(exitId, asha);
        assertThat(onExit.getUserStatus()).as("enabled on the other reader too").isEqualTo(ENABLED);
        assertThat(onExit.getValidFrom()).isEqualTo("2026-01-01T00:00:00+05:30");
        assertThat(onExit.getValidTo()).isEqualTo("2027-03-31T23:59:59+05:30");
    }

    @Test
    void cancellingTheOnlyMembershipDisablesTheMemberOnTheReadersAndKeepsItsDates() throws Exception {
        Member asha = importAsha();
        trustedRoster(exitId);
        String membership = paidMembership(asha);
        assertThat(projection(entranceId, asha).getUserStatus()).isEqualTo(ENABLED);

        cancel(membership);

        LocalDate today = LocalDate.now();
        for (String reader : new String[] {entranceId, exitId}) {
            DesiredMemberProjection row = projection(reader, asha);
            assertThat(row.getUserStatus()).as("disabled on %s", reader).isEqualTo(DISABLED);
            assertThat(row.getValidFrom()).as("the cancelled membership's dates, not the reader's")
                    .startsWith(today.minusDays(5).toString());
            assertThat(row.getValidTo()).startsWith(today.plusDays(25).toString());
        }
        Member stored = memberRepository.findById(asha.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("the member is not deactivated").isEqualTo(MemberStatus.ACTIVE);
        assertThat(stored.getDeviceEnabled()).isFalse();
    }

    @Test
    void aMemberWhoseOnlyMembershipIsCancelledIsSeededDisabledOnANewReader() throws Exception {
        Member om = onlyOn(entranceId, "Om", "Shah", "ID-OM", "7101");
        cancel(paidMembership(om));

        trustedRoster(exitId);

        assertThat(projection(exitId, om).getUserStatus()).isEqualTo(DISABLED);
    }

    @Test
    void aCancelledMembershipBesideARunningOneLeavesTheMemberEnabled() throws Exception {
        Member om = onlyOn(entranceId, "Om", "Shah", "ID-OM", "7101");
        String running = paidMembership(om);
        LocalDate today = LocalDate.now();
        String planId = readJson(postJson("/api/v1/plans",
                "{\"name\":\"Later\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String later = readJson(postJson("/api/v1/memberships", "{\"memberId\":\"" + om.getPublicId()
                + "\",\"planId\":\"" + planId + "\",\"startDate\":\"" + today.plusDays(40)
                + "\",\"endDate\":\"" + today.plusDays(70) + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();

        cancel(later);

        DesiredMemberProjection row = projection(entranceId, om);
        assertThat(row.getUserStatus()).isEqualTo(ENABLED);
        assertThat(row.getValidTo()).startsWith(today.plusDays(25).toString());

        cancel(running);

        assertThat(projection(entranceId, om).getUserStatus()).as("now every membership is cancelled")
                .isEqualTo(DISABLED);
    }

    private Member importAsha() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, READER_FROM, READER_TO, uploadFace(Color.RED)));
        postJson("/api/v1/devices/" + entranceId + "/import-pending", "{}").andExpect(status().isOk());
        ackEverything(entranceId);
        return memberRepository.findAll().stream()
                .filter(member -> "Asha Rao".equals(member.getFullName()))
                .findFirst()
                .orElseThrow();
    }

    private void cancel(String membershipId) throws Exception {
        postJson("/api/v1/memberships/" + membershipId + "/cancel", "{\"reason\":\"member request\"}")
                .andExpect(status().isOk());
    }
}
