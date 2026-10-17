package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.member.DeviceAuthority;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Terminal admin level: import from a reader, or staff edit on the member, reaches every reader. */
class ReaderAdminLevelIT extends AbstractReaderIT {

    private static final String FROM = "2025-03-23T19:03:35.0000000+05:30";
    private static final String TO = "2036-03-23T19:03:35.0000000+05:30";

    private String entranceId;
    private String exitId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("admin-level");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
    }

    @Test
    void anImportedAdministratorIsWrittenAsAdministratorToTheOtherReader() throws Exception {
        trustedRoster(exitId);
        trustedRoster(entranceId,
                person("1", "Rajat Dabas", 0, FROM, TO, uploadFace(Color.RED)).replace("\"Customer\"", "\"ADMIN\""),
                person("4", "Priyanka Dahiya", 0, FROM, TO, uploadFace(Color.BLUE)));

        postJson("/api/v1/devices/" + entranceId + "/import-pending", "{}").andExpect(status().isOk());
        trustedRoster(exitId);

        Member rajat = member("Rajat Dabas");
        assertThat(rajat.getDeviceAuthority()).isEqualTo(DeviceAuthority.ADMIN);
        assertThat(authorityOn(exitId, rajat)).isEqualTo("ADMIN");
        assertThat(authorityOn(entranceId, rajat)).isEqualTo("ADMIN");
        assertThat(authorityOn(exitId, member("Priyanka Dahiya"))).isEqualTo("Customer");
    }

    @Test
    void staffAdminChangePublishesAdminToEveryMappedReader() throws Exception {
        trustedRoster(entranceId,
                person("1", "Asha Shah", 0, FROM, TO, uploadFace(Color.RED)));
        postJson("/api/v1/devices/" + entranceId + "/import-pending", "{}").andExpect(status().isOk());
        trustedRoster(exitId);

        Member asha = member("Asha Shah");
        assertThat(asha.getDeviceAuthority()).isEqualTo(DeviceAuthority.USER);
        assertThat(authorityOn(entranceId, asha)).isEqualTo("Customer");
        assertThat(authorityOn(exitId, asha)).isEqualTo("Customer");

        putJson("/api/v1/members/" + asha.getPublicId(), """
                {"firstName":"Asha","lastName":"Shah","deviceAuthority":"ADMIN"}
                """).andExpect(status().isOk());

        Member updated = memberRepository.findById(asha.getId()).orElseThrow();
        assertThat(updated.getDeviceAuthority()).isEqualTo(DeviceAuthority.ADMIN);
        assertThat(authorityOn(entranceId, updated)).isEqualTo("ADMIN");
        assertThat(authorityOn(exitId, updated)).isEqualTo("ADMIN");
    }

    private String authorityOn(String readerPublicId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(readerPublicId), member.getId())
                .map(DesiredMemberProjection::getAuthority)
                .orElseThrow();
    }

    private Member member(String fullName) {
        return memberRepository.findAll().stream()
                .filter(member -> fullName.equals(member.getFullName()))
                .findFirst()
                .orElseThrow();
    }
}
