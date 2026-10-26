package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DeviceUserChangeService;
import com.example.gym.device.PendingEnrollmentService;
import com.example.gym.device.ReaderReconcileService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractReaderIT;
import com.example.gym.support.Scans;
import java.awt.Color;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** A person nobody decided on who is no longer on the reader is closed, and reopens if the id shows someone again. */
class ReaderPersonLeftIT extends AbstractReaderIT {

    private static final String FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String TO = "2026-12-31T23:59:59.0000000+05:30";

    @MockitoSpyBean
    private ReaderReconcileService reconcile;

    @MockitoSpyBean
    private PendingEnrollmentService pendingEnrollments;

    private String readerId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("person-left");
        readerId = createReader("Exit");
    }

    @Test
    void aLongReaderListIsSavedAndAMissingPersonIsStillClosed() throws Exception {
        int count = DeviceUserChangeService.ROSTER_BATCH + 5;
        String[] people = new String[count];
        for (int i = 0; i < count; i++) {
            people[i] = person(Integer.toString(i + 1), "Person " + (i + 1), 0, FROM, TO, null);
        }
        trustedRoster(readerId, people);
        assertThat(deviceObservedUserRepository.findByDeviceId(readerPk(readerId))).hasSize(count);

        String[] stillThere = new String[count - 1];
        System.arraycopy(people, 1, stillThere, 0, stillThere.length);
        trustedRoster(readerId, stillThere);

        assertThat(enrollment("1").isResolved()).isTrue();
        assertThat(enrollment("1").getReviewStatus()).isEqualTo(PendingEnrollment.GONE);
        assertThat(enrollment("2").isResolved()).isFalse();
        assertThat(deviceObservedUserRepository.findByDeviceId(readerPk(readerId))).hasSize(count);
    }

    @Test
    void aPersonMissingFromACompleteListIsClosedAndReopensWhenSeenAgain() throws Exception {
        String asha = person("1", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED));
        String ravi = person("2", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.BLUE));
        trustedRoster(readerId, asha, ravi);

        trustedRoster(readerId, ravi);

        assertThat(enrollment("1").isResolved()).isTrue();
        assertThat(enrollment("1").getReviewStatus()).isEqualTo(PendingEnrollment.GONE);
        assertThat(enrollment("2").isResolved()).isFalse();

        trustedRoster(readerId, asha, ravi);

        assertThat(enrollment("1").isResolved()).isFalse();
        assertThat(enrollment("1").getReviewStatus()).isEqualTo(PendingEnrollment.PENDING);
    }

    @Test
    void aFinishedRemovalIsQueuedAgainWhenTheReaderStillShowsThePerson() throws Exception {
        Member member = onlyOn(readerId, "abc", "Shah", "LEFT-ABC", "9301");
        String deviceUserId = projection(readerId, member).getDeviceUserId();
        ackEverything(readerId);
        mockMvc.perform(delete("/api/v1/members/" + member.getPublicId() + "/permanent")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        ackEverything(readerId);

        DesiredMemberProjection finished = desiredMemberProjectionRepository
                .findByDeviceIdAndDeviceUserId(readerPk(readerId), deviceUserId).orElseThrow();
        long closed = finished.getRevision();
        assertThat(finished.getMemberId()).isNull();
        assertThat(finished.isApplied()).isTrue();

        trustedRoster(readerId, person(deviceUserId, finished.getReaderName(), 0,
                finished.getValidFrom(), finished.getValidTo(), finished.getFaceSha256()));

        DesiredMemberProjection queued = desiredMemberProjectionRepository
                .findByDeviceIdAndDeviceUserId(readerPk(readerId), deviceUserId).orElseThrow();
        assertThat(queued.getRevision()).isGreaterThan(closed);
        assertThat(queued.isPresentOnReader()).isFalse();
        assertThat(queued.isApplied()).isFalse();
        JsonNode page = pull(readerId, closed);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("present").asBoolean()).isFalse();
        assertThat(page.get("items").get(0).get("deviceUserId").asString()).isEqualTo(deviceUserId);

        trustedRoster(readerId, person(deviceUserId, finished.getReaderName(), 0,
                finished.getValidFrom(), finished.getValidTo(), finished.getFaceSha256()));
        assertThat(desiredMemberProjectionRepository
                .findByDeviceIdAndDeviceUserId(readerPk(readerId), deviceUserId).orElseThrow().getRevision())
                .isEqualTo(queued.getRevision());
    }

    @Test
    void anEmptyListClosesEveryUndecidedPerson() throws Exception {
        trustedRoster(readerId,
                person("1", "Asha Rao", 0, FROM, TO, uploadFace(Color.RED)),
                person("2", "Ravi Iyer", 0, FROM, TO, uploadFace(Color.BLUE)));

        trustedRoster(readerId);

        assertThat(pendingEnrollmentRepository.findByDeviceIdAndResolvedFalse(readerPk(readerId))).isEmpty();
    }

    @Test
    void aFollowUpSeedFailureDoesNotUnsaveTheList() throws Exception {
        doThrow(new IllegalStateException("seed failed")).when(reconcile).seedAfter(any(), any(), anyInt());
        int count = DeviceUserChangeService.ROSTER_BATCH + 5;
        String[] people = new String[count];
        for (int i = 0; i < count; i++) {
            people[i] = person(Integer.toString(i + 1), "Person " + (i + 1), 0, FROM, TO, null);
        }
        String messageId = UUID.randomUUID().toString();
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(messageId, Instant.now(), gatewayPublicId, readerId,
                UUID.randomUUID(), Scans.stamp(roster(people)));
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());

        assertThat(gatewayMessageDedupeRepository.existsById(messageId)).isTrue();
        assertThat(deviceObservedUserRepository.findByDeviceId(readerPk(readerId))).hasSize(count);

        reset(reconcile);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
        assertThat(gatewayMessageDedupeRepository.existsById(messageId)).isTrue();
    }

    @Test
    void aSecondIdenticalLongListKeepsTheSameSavedPeople() throws Exception {
        int count = DeviceUserChangeService.ROSTER_BATCH + 5;
        String[] people = new String[count];
        for (int i = 0; i < count; i++) {
            people[i] = person(Integer.toString(i + 1), "Person " + (i + 1), 0, FROM, TO, null);
        }
        trustedRoster(readerId, people);
        assertThat(deviceObservedUserRepository.findByDeviceId(readerPk(readerId))).hasSize(count);
        assertThat(enrollment("1").isResolved()).isFalse();

        trustedRoster(readerId, people);

        assertThat(deviceObservedUserRepository.findByDeviceId(readerPk(readerId))).hasSize(count);
        assertThat(enrollment("1").isResolved()).isFalse();
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndResolvedFalse(readerPk(readerId))).hasSize(count);
    }

    @Test
    void aLongListRetriesAfterAMidBatchFailure() throws Exception {
        AtomicInteger batches = new AtomicInteger();
        doAnswer(invocation -> {
            if (batches.incrementAndGet() == 1) {
                throw new IllegalStateException("batch failed");
            }
            return invocation.callRealMethod();
        }).when(pendingEnrollments).observeRosterBatch(any(), any(), any());

        int count = DeviceUserChangeService.ROSTER_BATCH + 5;
        String[] people = new String[count];
        for (int i = 0; i < count; i++) {
            people[i] = person(Integer.toString(i + 1), "Person " + (i + 1), 0, FROM, TO, null);
        }
        String messageId = UUID.randomUUID().toString();
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(messageId, Instant.now(), gatewayPublicId, readerId,
                UUID.randomUUID(), Scans.stamp(roster(people)));

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
        assertThat(gatewayMessageDedupeRepository.existsById(messageId)).isFalse();

        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());

        assertThat(gatewayMessageDedupeRepository.existsById(messageId)).isTrue();
        assertThat(deviceObservedUserRepository.findByDeviceId(readerPk(readerId))).hasSize(count);
        assertThat(enrollment("1").isResolved()).isFalse();
    }

    private PendingEnrollment enrollment(String deviceUserId) {
        return pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(readerId), deviceUserId).orElseThrow();
    }
}
