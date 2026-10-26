package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.ReaderReconcileService;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractReaderIT;
import com.example.gym.support.Scans;
import java.awt.Color;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * Observations that reach the server late. Every seed trigger is tried against a reader whose
 * newest scan, already taken by the gateway, shows a person who may be the seeded member.
 */
class StaleObservationIT extends AbstractReaderIT {

    private static final String FROM = "2026-02-01T00:00:00.0000000+05:30";
    private static final String TO = "2026-12-31T23:59:59.0000000+05:30";

    @Autowired
    private ReaderReconcileService readerReconcileService;

    @Autowired
    private GatewayMessageService gatewayMessageService;

    private String entranceId;
    private String exitId;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("stale");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
    }

    enum Trigger { TRUSTED_ROSTER, REACTIVATION, FIRST_PHOTO, STAFF_RECONCILE, STAFF_CREATE }

    /**
     * The reported defect. The gateway scanned Om Shah at id 7 on the exit reader; that scan has not
     * been processed when a seed trigger puts the server's Om Shah on the exit reader. The gateway
     * cannot pull while its scan is unprocessed, and processing it withdraws the unwritten seed, so
     * the reader never gets Om a second time.
     */
    @ParameterizedTest
    @EnumSource(Trigger.class)
    void aSeedTakenBeforeALateScanOfTheSamePersonIsWithdrawnBeforeTheGatewayCanWriteIt(Trigger trigger)
            throws Exception {
        long emptyList = 0;
        if (trigger != Trigger.TRUSTED_ROSTER) {
            emptyList = scanNow(exitId, roster());
            scanNow(entranceId, roster());
        }
        String personSeven = person("7", "Om Shah", 0, FROM, TO, uploadFace(Color.GREEN));
        long lateScan = emptyList + 1_000;

        Member om = seed(trigger, emptyList);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .as("the trigger seeded the exit reader before the late scan arrived").isPresent();

        readerSaysAt(exitId, JOURNAL, lateScan, roster(personSeven));

        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .as("the unwritten seed is withdrawn").isEmpty();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .isEmpty();
        for (JsonNode item : pull(exitId, 0).get("items")) {
            assertThat(item.get("name").asString()).as("nothing for Om is pulled on the exit reader")
                    .isNotEqualTo("Om Shah");
        }
        PendingEnrollment seven = enrollment(exitId, "7");
        assertThat(seven.isResolved()).isFalse();
        assertThat(seven.getDecision()).as("nothing is linked automatically").isNull();
        assertThat(memberRepository.count()).as("nothing is created automatically").isEqualTo(1);
        if (trigger != Trigger.TRUSTED_ROSTER) {
            assertThat(projection(entranceId, om).isPresentOnReader())
                    .as("a reader showing nobody who may be Om keeps him").isTrue();
        }

        postJson("/api/v1/reviews/" + seven.getPublicId() + "/link", "{\"memberId\":\"" + om.getPublicId() + "\"}")
                .andExpect(status().isOk());
        ackEverything(exitId);
        assertThat(memberDeviceMappingRepository.findByMemberId(om.getId()))
                .filteredOn(mapping -> mapping.getDeviceId().equals(readerPk(exitId)))
                .singleElement()
                .satisfies(mapping -> assertThat(mapping.getDeviceUserId())
                        .as("one identity on the exit reader: the reader's own id").isEqualTo("7"));
        assertThat(enrollment(exitId, "7").isResolved()).isTrue();
    }

    /**
     * After the seed is withdrawn, staff decide person 7 is someone else. The server's Om is then
     * seeded at a new id on the exit reader; id 7 stays with the person the reader shows.
     */
    @Test
    void aWithdrawnSeedIsSeededAgainAtANewIdOnceStaffSayThePersonIsSomeoneElse() throws Exception {
        String personSeven = person("7", "Om Shah", 0, FROM, TO, uploadFace(Color.GREEN));
        Member om = seed(Trigger.TRUSTED_ROSTER, 0);
        readerSaysAt(exitId, JOURNAL, 1_000, roster(personSeven));
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId())).isEmpty();

        postJson("/api/v1/reviews/" + enrollment(exitId, "7").getPublicId() + "/create", "{}")
                .andExpect(status().isOk());
        ackEverything(exitId, JOURNAL, 1_000);
        assertThat(enrollment(exitId, "7").isResolved()).as("the decision is verified on the reader").isTrue();
        readerSaysAt(exitId, JOURNAL, 1_001, roster(personSeven));

        assertThat(memberRepository.count()).isEqualTo(2);
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .hasValueSatisfying(mapping -> assertThat(mapping.getDeviceUserId())
                        .as("Om gets an id of his own").isNotEqualTo("7"));
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(readerPk(exitId), "7"))
                .hasValueSatisfying(mapping -> assertThat(mapping.getMemberId()).isNotEqualTo(om.getId()));
        assertThat(pull(exitId, 0).get("items")).anySatisfy(item ->
                assertThat(item.get("name").asString()).isEqualTo("Om Shah"));
    }

    @Test
    void aScanOlderThanAProcessedListOrSnapshotChangesNothing() throws Exception {
        String face = uploadFace(Color.GREEN);
        long older = Scans.next();
        long newer = scanNow(exitId, roster(person("7", "Om Shah", 0, FROM, TO, face)));

        readerSaysAt(exitId, JOURNAL, older, person("7", "Old Name", 0, FROM, TO, face));
        readerSaysAt(exitId, JOURNAL, older, roster());

        assertThat(snapshot(exitId, "7").getReaderName()).isEqualTo("Om Shah");
        assertThat(snapshot(exitId, "7").getObservedSeq()).isEqualTo(newer);
        assertThat(deviceRepository.findById(readerPk(exitId)).orElseThrow().isRosterTrustedEmpty())
                .as("an older empty list does not make the reader look empty").isFalse();

        readerSaysAt(exitId, JOURNAL, Scans.next(), person("7", "Om R Shah", 0, FROM, TO, face));
        assertThat(snapshot(exitId, "7").getReaderName()).as("a later scan is applied").isEqualTo("Om R Shah");
    }

    @Test
    void theSameScanDeliveredAgainUnderNewMessageIdsIsAppliedOnce() throws Exception {
        String face = uploadFace(Color.GREEN);
        long scan = Scans.next();
        String list = roster(person("7", "Om Shah", 0, FROM, TO, face));
        readerSaysAt(exitId, JOURNAL, scan, list);
        readerSaysAt(exitId, JOURNAL, scan, list);
        readerSaysAt(exitId, JOURNAL, scan, person("7", "Om Shah", 0, FROM, TO, face));

        assertThat(pendingEnrollmentRepository.findAll()).singleElement()
                .satisfies(enrollment -> assertThat(enrollment.getDeviceUserId()).isEqualTo("7"));
        assertThat(deviceObservedUserRepository.findAll()).hasSize(1);
        assertThat(deviceReviewItemRepository.findAll()).isEmpty();
    }

    /**
     * Staff linked the reader's person 7 to Om and the gateway verified it after scan S. A scan
     * from S or earlier that shows someone else at 7 cannot undo the link or reopen the person.
     */
    @Test
    void aScanTakenBeforeAVerifiedLinkCannotUndoItButALaterOneIsSeen() throws Exception {
        String face = uploadFace(Color.GREEN);
        Member om = withFace(saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101"));
        scanNow(exitId, roster(person("7", "Om Shah", 0, FROM, TO, face)));
        linkSeven(om);
        long readBack = Scans.next();
        ackEverything(exitId, JOURNAL, readBack);
        assertThat(enrollment(exitId, "7").isResolved()).isTrue();

        String someoneElse = person("7", "Asha Rao", 0, FROM, TO, uploadFace(Color.BLUE));
        readerSaysAt(exitId, JOURNAL, readBack - 1, someoneElse);
        readerSaysAt(exitId, JOURNAL, readBack, someoneElse);
        readerSaysAt(exitId, JOURNAL, readBack, roster(someoneElse));

        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .hasValueSatisfying(mapping -> assertThat(mapping.getDeviceUserId()).isEqualTo("7"));
        assertThat(enrollment(exitId, "7").isResolved()).as("the decision is not reopened").isTrue();
        assertThat(deviceReviewItemRepository.findByTenantIdAndResolvedFalse(tenant.getId())).isEmpty();
        assertThat(snapshot(exitId, "7").getReaderName()).isEqualTo("Om Shah");

        readerSaysAt(exitId, JOURNAL, Scans.next(), someoneElse);

        assertThat(snapshot(exitId, "7").getReaderName()).as("a scan after the read-back counts")
                .isEqualTo("Asha Rao");
        boolean reopened = !enrollment(exitId, "7").isResolved()
                || !deviceReviewItemRepository.findByTenantIdAndResolvedFalse(tenant.getId()).isEmpty();
        assertThat(reopened).as("someone else at Om's id after the link goes to staff").isTrue();
    }

    /**
     * Om was seeded and verified after scan S. A list from scan S, taken before the write, does
     * not show him; delivered late it is not a disappearance. A later list without him is.
     */
    @Test
    void aListTakenBeforeAVerifiedWriteIsNotADisappearance() throws Exception {
        scanNow(exitId, roster());
        Member om = withFace(saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101"));
        postJson("/api/v1/devices/" + exitId + "/reconcile", "{}").andExpect(status().isOk());
        long readBack = Scans.next();
        ackEverything(exitId, JOURNAL, readBack);

        readerSaysAt(exitId, JOURNAL, readBack, roster());

        assertThat(projection(exitId, om).isHeld()).isFalse();
        assertThat(deviceReviewItemRepository.findByTenantIdAndResolvedFalse(tenant.getId())).isEmpty();

        readerSaysAt(exitId, JOURNAL, Scans.next(), roster());

        assertThat(deviceReviewItemRepository.findByTenantIdAndResolvedFalse(tenant.getId()))
                .as("a list after the read-back without Om is his disappearance").isNotEmpty();
    }

    @Test
    void anObservationWithoutAScanOrderIsRefusedAndRecordedSoTheGatewayCanDropIt() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String reply = sendUnchecked(exitId, messageId, roster(person("7", "Om Shah", 0, FROM, TO, null)));

        assertThat(reply).contains("observation out of order");
        assertThat(reply).contains(messageId);
        assertThat(reply).contains("\"retryable\":false");
        assertThat(gatewayMessageDedupeRepository.existsById(messageId)).isTrue();
        assertThat(observed(exitId, messageId)).as("a terminal refusal is recorded so the fence can open").isTrue();
        assertThat(deviceObservedUserRepository.findAll()).isEmpty();
        assertThat(pendingEnrollmentRepository.findAll()).isEmpty();
    }

    /**
     * The gateway's reader journal was replaced. Its numbering starts again and is accepted; scans
     * of the old journal still in flight are refused rather than compared.
     */
    @Test
    void aReplacedJournalIsAdoptedAndTheOldOneRefused() throws Exception {
        String face = uploadFace(Color.GREEN);
        readerSaysAt(exitId, "old-journal", 500, roster(person("7", "Om Shah", 0, FROM, TO, face)));
        readerSaysAt(exitId, "new-journal", 1, roster(person("7", "Om R Shah", 0, FROM, TO, face)));

        String late = UUID.randomUUID().toString();
        String reply = sendUnchecked(exitId, late, Scans.stamp(
                roster(person("7", "Stale Name", 0, FROM, TO, face)), "old-journal", 501));

        assertThat(reply).contains("observation out of order");
        assertThat(reply).contains(late);
        assertThat(reply).contains("\"retryable\":false");
        assertThat(observed(exitId, late)).as("a refused old-journal scan is recorded so it is not resent forever").isTrue();
        assertThat(snapshot(exitId, "7").getReaderName()).isEqualTo("Om R Shah");
        assertThat(readerRevisionRepository.findByDeviceId(readerPk(exitId)).orElseThrow().getObservationEpoch())
                .isEqualTo("new-journal");
    }

    /** A seed the gateway may have written is never withdrawn: once pulled, or once verified. */
    @Test
    void aSeedTheGatewayWasSentIsNotWithdrawn() throws Exception {
        scanNow(exitId, roster());
        scanNow(entranceId, roster());
        Member om = withFace(saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101"));
        postJson("/api/v1/devices/" + exitId + "/reconcile", "{}").andExpect(status().isOk());
        postJson("/api/v1/devices/" + entranceId + "/reconcile", "{}").andExpect(status().isOk());
        pull(exitId, 0);
        ackEverything(entranceId);
        String face = uploadFace(Color.GREEN);

        readerSaysAt(exitId, JOURNAL, Scans.next(), roster(person("7", "Om Shah", 0, FROM, TO, face)));
        readerSaysAt(entranceId, JOURNAL, Scans.next(), person("7", "Om Shah", 0, FROM, TO, face));

        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .as("pulled, so it may be on the reader").isPresent();
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(readerPk(entranceId), om.getId()))
                .as("verified on the reader").isPresent();
        assertThat(enrollment(exitId, "7").getDecision()).as("person 7 is still for staff").isNull();
        assertThat(enrollment(entranceId, "7").getDecision()).isNull();
    }

    /**
     * Seeding and the processing of a scan that shows a possible match run at the same time, many
     * times. Each pair finishes, and neither order leaves the member seeded on that reader.
     */
    @Test
    void aSeedRacingAScanOfTheSamePersonFinishesAndNeverKeepsTheSeed() throws Exception {
        scanNow(exitId, roster());
        String face = uploadFace(Color.GREEN);
        Device exit = deviceRepository.findById(readerPk(exitId)).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String name = "Om Shah " + round;
                Member om = withFace(saveMember(tenant.getId(), "ID-OM-" + round, "Om", "Shah " + round,
                        "71" + round));
                String scan = Scans.stamp(person("7" + round, name, 0, FROM, TO, face));
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> seeding = pool.submit(() -> {
                    await(start);
                    readerReconcileService.reconcile(exit);
                    return null;
                });
                Future<?> observing = pool.submit(() -> {
                    await(start);
                    gatewayMessageService.process(envelope(exitId, UUID.randomUUID().toString(), scan),
                            gatewayPublicId);
                    return null;
                });
                seeding.get(60, TimeUnit.SECONDS);
                observing.get(60, TimeUnit.SECONDS);

                assertThat(memberDeviceMappingRepository.findByDeviceIdAndMemberId(exit.getId(), om.getId()))
                        .as("round %d: the possible match is not seeded", round).isEmpty();
                assertThat(enrollment(exitId, "7" + round).getDecision()).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void linkSeven(Member member) throws Exception {
        postJson("/api/v1/reviews/" + enrollment(exitId, "7").getPublicId() + "/link",
                "{\"memberId\":\"" + member.getPublicId() + "\"}").andExpect(status().isOk());
    }

    private DeviceObservedUser snapshot(String readerPublicId, String deviceUserId) {
        return deviceObservedUserRepository.findByDeviceIdAndDeviceUserId(readerPk(readerPublicId), deviceUserId)
                .orElseThrow();
    }

    private String envelope(String readerPublicId, String messageId, String payload) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(messageId, Instant.now(), gatewayPublicId, readerPublicId, UUID.randomUUID(), payload);
    }

    private String sendUnchecked(String readerPublicId, String messageId, String payload) throws Exception {
        return mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(readerPublicId, messageId, payload)))
                .andReturn().getResponse().getContentAsString();
    }

    private boolean observed(String readerPublicId, String messageId) throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/desired/observed")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + readerPublicId + "\",\"messageIds\":[\"" + messageId + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("processed").asBoolean();
    }

    private Member seed(Trigger trigger, long emptyList) throws Exception {
        switch (trigger) {
            case TRUSTED_ROSTER -> {
                Member om = withFace(saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101"));
                readerSaysAt(exitId, JOURNAL, emptyList + 1, roster());
                return om;
            }
            case REACTIVATION -> {
                Member om = saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101");
                om.setStatus(MemberStatus.INACTIVE);
                om = withFace(memberRepository.save(om));
                postJson("/api/v1/members/" + om.getPublicId() + "/reactivate", "{}")
                        .andExpect(status().is2xxSuccessful());
                return memberRepository.findById(om.getId()).orElseThrow();
            }
            case FIRST_PHOTO -> {
                Member om = saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101");
                mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/members/" + om.getPublicId() + "/face")
                                .file(new MockMultipartFile("file", "face.jpg", MediaType.IMAGE_JPEG_VALUE, faceJpeg()))
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk());
                return om;
            }
            case STAFF_RECONCILE -> {
                Member om = withFace(saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101"));
                postJson("/api/v1/devices/" + exitId + "/reconcile", "{}").andExpect(status().isOk());
                postJson("/api/v1/devices/" + entranceId + "/reconcile", "{}").andExpect(status().isOk());
                return om;
            }
            case STAFF_CREATE -> {
                String id = createMemberId(token, """
                        {"firstName":"Om","lastName":"Shah","memberCode":"ID-OM","serialNumber":"7101"}
                        """);
                return memberRepository.findByPublicId(id).orElseThrow();
            }
            default -> throw new IllegalArgumentException(trigger.name());
        }
    }

    private Member withFace(Member member) throws Exception {
        memberFaceService.storeUploaded(member, faceJpeg());
        return member;
    }

    /** One scan the gateway takes and delivers now. Returns its number. */
    private long scanNow(String readerPublicId, String payload) throws Exception {
        long seq = Scans.next();
        readerSaysAt(readerPublicId, JOURNAL, seq, payload);
        return seq;
    }

    private PendingEnrollment enrollment(String readerPublicId, String deviceUserId) {
        return pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(readerPk(readerPublicId), deviceUserId)
                .orElseThrow();
    }
}
