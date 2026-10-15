package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractReaderIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Enabled or disabled, and the photo, are reader state like the name and dates: a change made at
 * the reader opens a review item and never reaches the member or another reader by itself.
 */
class ReaderStatusAndPhotoReviewIT extends AbstractReaderIT {

    private static final String OTHER_PHOTO = "a".repeat(64);

    private String entranceId;
    private Member asha;
    private DesiredMemberProjection written;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("status-review");
        entranceId = createReader("Entrance");
        asha = onlyOn(entranceId, "Asha", "Rao", "STATUS-ASHA", "8101");
        ackEverything(entranceId);
        written = projection(entranceId, asha);
    }

    @Test
    void aReaderSideDisableOpensAReviewAndLeavesTheServerAsItIs() throws Exception {
        readerSays(entranceId, asDesired(written, 1, written.getFaceSha256()));

        DeviceReviewItem item = onlyReview();
        assertThat(item.isResolved()).isFalse();
        assertThat(item.getServerUserStatus()).isZero();
        assertThat(item.getReaderUserStatus()).isEqualTo(1);
        assertThat(item.getBaselineUserStatus()).isZero();
        JsonNode shown = openReviews();
        assertThat(shown.get("readerUserStatus").asInt()).isEqualTo(1);
        assertThat(shown.get("serverUserStatus").asInt()).isZero();
        assertThat(shown.get("readerPhotoDiffers").asBoolean()).isFalse();
        assertUnchanged();

        postJson("/api/v1/reviews/" + item.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());
        DesiredMemberProjection republished = projection(entranceId, asha);
        assertThat(republished.getRevision()).isGreaterThan(written.getRevision());
        assertThat(republished.getUserStatus()).isZero();
        ackEverything(entranceId);
        assertThat(onlyReview().isResolved()).isTrue();
    }

    @Test
    void aReaderSidePhotoChangeOpensAReviewAndKeepsTheServerPhoto() throws Exception {
        readerSays(entranceId, asDesired(written, 0, OTHER_PHOTO));

        DeviceReviewItem item = onlyReview();
        assertThat(item.getReaderFaceSha256()).isEqualTo(OTHER_PHOTO);
        assertThat(item.getServerFaceSha256()).isEqualTo(written.getFaceSha256());
        assertThat(openReviews().get("readerPhotoDiffers").asBoolean()).isTrue();
        assertUnchanged();
    }

    @Test
    void theSameStatusAndPhotoOpenNothing() throws Exception {
        readerSays(entranceId, asDesired(written, 0, written.getFaceSha256()));
        trustedRoster(entranceId, asDesired(written, 0, written.getFaceSha256()));

        assertThat(deviceReviewItemRepository.count()).isZero();
        assertUnchanged();
    }

    @Test
    void aServerDisableTheReaderHasNotAppliedYetIsNotAReaderChange() throws Exception {
        mockMvc.perform(delete("/api/v1/members/" + asha.getPublicId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(projection(entranceId, asha).getUserStatus()).isEqualTo(1);

        readerSays(entranceId, asDesired(written, 0, written.getFaceSha256()));

        assertThat(deviceReviewItemRepository.count()).isZero();
    }

    private DeviceReviewItem onlyReview() {
        return deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(readerPk(entranceId),
                written.getDeviceUserId()).orElseThrow();
    }

    private JsonNode openReviews() throws Exception {
        JsonNode rows = getJson("/api/v1/reviews");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void assertUnchanged() {
        DesiredMemberProjection now = projection(entranceId, asha);
        assertThat(now.getRevision()).isEqualTo(written.getRevision());
        assertThat(now.getUserStatus()).isZero();
        assertThat(now.getFaceSha256()).isEqualTo(written.getFaceSha256());
        assertThat(memberRepository.findById(asha.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(deviceSyncCommandRepository.count()).isZero();
    }
}
