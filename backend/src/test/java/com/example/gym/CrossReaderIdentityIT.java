package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditLog;
import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.support.AbstractReaderIT;
import java.awt.Color;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * Identity across readers: one person is one member, whichever reader is imported first or at the
 * same time; a member's data never comes from another reader's row; every seed trigger holds a
 * member back from a reader that shows an undecided person who may be them; and review items stay
 * per reader and per episode, listed under their member.
 */
class CrossReaderIdentityIT extends AbstractReaderIT {

    private static final String ASHA_FROM = "2026-01-01T00:00:00.0000000+05:30";
    private static final String ASHA_TO = "2027-03-31T23:59:59.0000000+05:30";
    private static final String OTHER_FROM = "2026-02-01T00:00:00.0000000+05:30";
    private static final String OTHER_TO = "2026-12-31T23:59:59.0000000+05:30";

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String entranceId;
    private String exitId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        gymWithGateway("identity");
        entranceId = createReader("Entrance");
        exitId = createReader("Exit");
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }


    @Test
    void theMemberPageShowsAReaderThatAlreadyHasTheSamePhoto() throws Exception {
        String face = uploadFace(Color.RED);
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, face));
        trustedRoster(exitId,
                person("6", "Asha Rao", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.BLUE)),
                person("7", "Asha Rao", 0, OTHER_FROM, OTHER_TO, face));
        assertThat(importPending(entranceId).get("created").asInt()).isEqualTo(1);
        Member asha = member("Asha Rao");

        JsonNode exit = readerRow(getJson("/api/v1/members/" + asha.getPublicId() + "/device-sync").get("devices"));
        assertThat(exit.get("deviceUserId").isNull()).isTrue();
        assertThat(exit.get("seenDeviceUserId").asString()).isEqualTo("7");
        assertThat(exit.get("validFrom").asString()).isEqualTo(OTHER_FROM);
        assertThat(exit.get("validTo").asString()).isEqualTo(OTHER_TO);
        assertThat(exit.get("facePresent").asBoolean()).isTrue();

        JsonNode listed = readerRow(getJson("/api/v1/members/" + asha.getPublicId()).get("readers"));
        assertThat(listed.get("deviceUserId").asString()).isEqualTo("7");
        assertThat(listed.get("syncState").asString()).isEqualTo("seen");
    }

    private static JsonNode readerRow(JsonNode rows) {
        for (JsonNode row : rows) {
            if ("Exit".equals(row.get("deviceName").asString())) {
                return row;
            }
        }
        throw new AssertionError("Exit row missing");
    }

    @Test
    void importingASecondReaderLeavesAPersonWhoMayAlreadyBeAMemberPending() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, uploadFace(Color.RED)));
        trustedRoster(exitId,
                person("7", "  asha   RAO ", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.GREEN)),
                person("8", "Ravi Iyer", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.BLUE)));

        assertThat(importPending(entranceId).get("created").asInt()).isEqualTo(1);
        Member asha = member("Asha Rao");
        JsonNode second = importPending(exitId);

        assertThat(second.get("created").asInt()).as("only the person nobody could be").isEqualTo(1);
        assertThat(second.get("skipped")).singleElement().satisfies(row -> {
            assertThat(row.get("deviceUserId").asString()).isEqualTo("7");
            assertThat(row.get("reason").asString()).contains(asha.getMemberCode());
        });
        assertThat(memberRepository.findAll()).extracting(Member::getFullName)
                .containsExactlyInAnyOrder("Asha Rao", "Ravi Iyer");
        PendingEnrollment seven = enrollment(exitId, "7");
        assertThat(seven.isResolved()).isFalse();
        assertThat(seven.getDecision()).isNull();
        assertThat(memberDeviceMappingRepository.findByMemberId(asha.getId())).hasSize(1);

        JsonNode listed = openItem(seven.getPublicId());
        assertThat(listed.get("possibleMemberId").asString()).isEqualTo(asha.getPublicId());
        assertThat(listed.get("possibleMemberName").asString()).isEqualTo("Asha Rao");
        assertThat(listed.get("deviceName").asString()).isEqualTo("Exit");
    }

    @Test
    void aCreateWaitingForTheReaderIsNotFlaggedAsAPossibleMatchOfTheMemberItMade() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, uploadFace(Color.RED)));
        assertThat(importPending(entranceId).get("created").asInt()).isEqualTo(1);
        PendingEnrollment eleven = enrollment(entranceId, "11");
        assertThat(eleven.getDecision()).as("the reader has not confirmed yet").isNotNull();
        assertThat(eleven.isResolved()).isFalse();

        JsonNode listed = getJson("/api/v1/reviews");
        for (JsonNode item : listed) {
            if (eleven.getPublicId().equals(item.get("id").asString())) {
                assertThat(item.path("possibleMemberId").isMissingNode() || item.get("possibleMemberId").isNull())
                        .as("the member this decision created is not a second candidate").isTrue();
            }
        }
        JsonNode again = importPending(entranceId);
        assertThat(again.get("created").asInt()).isZero();
        for (JsonNode row : again.get("skipped")) {
            assertThat(row.get("reason").asString()).doesNotContain("May already be");
        }
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    void theSamePhotoUnderAnotherNameIsAPossibleMatchToo() throws Exception {
        String face = uploadFace(Color.RED);
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, face));
        importPending(entranceId);
        trustedRoster(exitId, person("7", "A Rao", 0, OTHER_FROM, OTHER_TO, face));

        JsonNode second = importPending(exitId);

        assertThat(second.get("created").asInt()).isZero();
        assertThat(memberRepository.count()).isEqualTo(1);
        assertThat(enrollment(exitId, "7").getDecision()).isNull();
    }

    @Test
    void twoReadersImportedAtTheSameTimeCreateEachSharedPersonOnce() throws Exception {
        String[] names = {"Asha Rao", "Ravi Iyer", "Om Shah", "Lila Das", "Kiran Bose"};
        Color[] colors = {Color.RED, Color.BLUE, Color.GREEN, Color.ORANGE, Color.MAGENTA};
        List<String> entrance = new ArrayList<>();
        List<String> exit = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            entrance.add(person(Integer.toString(10 + i), names[i], 0, ASHA_FROM, ASHA_TO, uploadFace(colors[i])));
            exit.add(person(Integer.toString(20 + i), names[i], 0, OTHER_FROM, OTHER_TO,
                    uploadFace(colors[i].darker())));
        }
        trustedRoster(entranceId, entrance.toArray(String[]::new));
        trustedRoster(exitId, exit.toArray(String[]::new));

        List<JsonNode> results = runTogether(List.of(() -> importPending(entranceId), () -> importPending(exitId)));

        int created = results.stream().mapToInt(result -> result.get("created").asInt()).sum();
        int skipped = results.stream().mapToInt(result -> result.get("skipped").size()).sum();
        assertThat(created).as("one member per person").isEqualTo(names.length);
        assertThat(skipped).isEqualTo(names.length);
        assertThat(memberRepository.findAll()).extracting(Member::getFullName)
                .containsExactlyInAnyOrder(names);
        for (Member member : memberRepository.findAll()) {
            assertThat(memberDeviceMappingRepository.findByMemberId(member.getId()))
                    .as("each member is on the reader it came from, never copied").hasSize(1);
        }
    }


    @Test
    void aSecondReaderGetsTheMembersOwnAdoptedDatesNotWhateverAnotherReadersRowHolds() throws Exception {
        trustedRoster(entranceId, person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, uploadFace(Color.RED)));
        importPending(entranceId);
        ackEverything(entranceId);
        Member asha = member("Asha Rao");
        assertThat(asha.getAdoptedValidFrom()).isEqualTo("2026-01-01T00:00:00+05:30");
        assertThat(asha.getAdoptedValidTo()).isEqualTo("2027-03-31T23:59:59+05:30");

        DesiredMemberProjection onEntrance = projection(entranceId, asha);
        onEntrance.setValidFrom("2020-01-01T00:00:00+05:30");
        onEntrance.setValidTo("2020-01-31T23:59:59+05:30");
        desiredMemberProjectionRepository.save(onEntrance);

        trustedRoster(exitId);

        DesiredMemberProjection onExit = projection(exitId, asha);
        assertThat(onExit.getValidFrom()).as("from the member, not the entrance row")
                .isEqualTo("2026-01-01T00:00:00+05:30");
        assertThat(onExit.getValidTo()).isEqualTo("2027-03-31T23:59:59+05:30");
    }

    @Test
    void aServerMemberWithoutAMembershipGetsTodayOnEveryReaderWhateverAnImportedRowHolds() throws Exception {
        Member om = onlyOn(entranceId, "Om", "Shah", "ID-OM", "7101");
        DesiredMemberProjection onEntrance = projection(entranceId, om);
        onEntrance.setValidFrom("2020-01-01T00:00:00+05:30");
        desiredMemberProjectionRepository.save(onEntrance);

        trustedRoster(exitId);

        String today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        assertThat(projection(exitId, om).getValidFrom()).startsWith(today);
        assertThat(memberRepository.findById(om.getId()).orElseThrow().getAdoptedValidFrom()).isNull();
    }

    @Test
    void aMemberCreatedDisabledIsAuditedWithItsInactiveStatus() throws Exception {
        trustedRoster(entranceId, person("12", "Ravi Iyer", 1, OTHER_FROM, OTHER_TO, uploadFace(Color.BLUE)));
        importPending(entranceId);
        Member ravi = member("Ravi Iyer");
        assertThat(ravi.getStatus()).isEqualTo(MemberStatus.INACTIVE);

        assertThat(auditLogRepository.findAll())
                .filteredOn(row -> "ENROLLMENT_DECIDED".equals(row.getAction())
                        && row.getDetails() != null && row.getDetails().contains(ravi.getPublicId()))
                .singleElement()
                .extracting(AuditLog::getDetails)
                .satisfies(details -> assertThat(details)
                        .contains("\"memberStatus\":\"INACTIVE\"")
                        .contains("the reader showed this person disabled"));
    }


    @Test
    void reactivationDoesNotSeedAMemberOntoAReaderShowingAnUndecidedPersonWhoMayBeThem() throws Exception {
        Member om = saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101");
        om.setStatus(MemberStatus.INACTIVE);
        om = memberRepository.save(om);
        memberFaceService.storeUploaded(om, faceJpeg());
        String omId = om.getPublicId();
        trustedRoster(exitId, person("7", "Om Shah", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.GREEN)));

        postJson("/api/v1/members/" + omId + "/reactivate", "{}").andExpect(status().is2xxSuccessful());

        assertThat(memberRepository.findById(om.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(projection(entranceId, om).isPresentOnReader()).as("a reader with nobody undecided gets Om")
                .isTrue();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), om.getId()))
                .as("person 7 may be Om; Om waits for the staff decision").isEmpty();
        assertThat(enrollment(exitId, "7").getDecision()).isNull();

        postJson("/api/v1/reviews/" + enrollment(exitId, "7").getPublicId() + "/reject", "{}")
                .andExpect(status().isOk());
        ackEverything(exitId);
        trustedRoster(exitId);
        assertThat(projection(exitId, om).isPresentOnReader()).as("seeded once nobody is undecided").isTrue();
    }

    @Test
    void aFirstPhotoSeedsEveryReaderExceptOneShowingAnUndecidedPersonWhoMayBeTheMember() throws Exception {
        Member nia = saveMember(tenant.getId(), "ID-NIA", "Nia", "Das", "7102");
        trustedRoster(exitId, person("7", "Nia Das", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.GREEN)));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/members/" + nia.getPublicId() + "/face")
                        .file(new MockMultipartFile("file", "face.jpg", MediaType.IMAGE_JPEG_VALUE, faceJpeg()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(projection(entranceId, nia).isPresentOnReader()).isTrue();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), nia.getId()))
                .isEmpty();
    }

    @Test
    void theReconcileButtonHoldsTheSameMembersAndSaysHowMany() throws Exception {
        Member nia = saveMember(tenant.getId(), "ID-NIA", "Nia", "Das", "7102");
        memberFaceService.storeUploaded(nia, faceJpeg());
        Member om = saveMember(tenant.getId(), "ID-OM", "Om", "Shah", "7101");
        memberFaceService.storeUploaded(om, faceJpeg());
        readerSays(exitId, person("7", "Nia Das", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.GREEN)));

        JsonNode done = readJson(postJson("/api/v1/devices/" + exitId + "/reconcile", "{}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(done.get("written").asInt()).isEqualTo(1);
        assertThat(done.get("held").asInt()).isEqualTo(1);
        assertThat(projection(exitId, om).isPresentOnReader()).isTrue();
        assertThat(desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), nia.getId()))
                .isEmpty();
    }

    @Test
    void deactivationRacingASeedLeavesNoEnabledRowForTheInactiveMember() throws Exception {
        List<Member> racers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Member member = saveMember(tenant.getId(), "RACE-" + i, "Racer" + i, "Shah", Integer.toString(7200 + i));
            memberFaceService.storeUploaded(member, faceJpeg());
            racers.add(member);
        }
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(() -> postJson("/api/v1/devices/" + exitId + "/reconcile", "{}")
                .andReturn().getResponse().getStatus());
        for (Member member : racers) {
            calls.add(() -> mockMvc.perform(delete("/api/v1/members/" + member.getPublicId())
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus());
        }

        assertThat(runTogether(calls)).allMatch(code -> code >= 200 && code < 300);

        for (Member member : racers) {
            assertThat(memberRepository.findById(member.getId()).orElseThrow().getStatus())
                    .isEqualTo(MemberStatus.INACTIVE);
            desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(exitId), member.getId())
                    .filter(DesiredMemberProjection::isPresentOnReader)
                    .ifPresent(row -> assertThat(row.getUserStatus())
                            .as("a seed that won the race is disabled by the deactivation after it").isEqualTo(1));
        }
    }


    @Test
    void aResolvedReviewOnOneReaderStaysClosedWhenAnotherReaderDevelopsItsOwnConflict() throws Exception {
        String ashaId = createMemberId(token, """
                {"firstName":"Asha","lastName":"Shah","memberCode":"ID-ASHA","serialNumber":"7103"}
                """);
        Member asha = memberRepository.findByPublicId(ashaId).orElseThrow();
        ackEverything(entranceId);
        ackEverything(exitId);
        DesiredMemberProjection onEntrance = projection(entranceId, asha);
        readerSays(entranceId, person(onEntrance.getDeviceUserId(), "Changed At Entrance", 0,
                onEntrance.getValidFrom(), onEntrance.getValidTo(), onEntrance.getFaceSha256()));
        DeviceReviewItem first = openReview(entranceId, asha);
        postJson("/api/v1/reviews/" + first.getPublicId() + "/accept-server", "{}").andExpect(status().isOk());
        ackEverything(entranceId);
        assertThat(deviceReviewItemRepository.findByPublicId(first.getPublicId()).orElseThrow().isResolved()).isTrue();

        DesiredMemberProjection onExit = projection(exitId, asha);
        readerSays(exitId, person(onExit.getDeviceUserId(), "Changed At Exit", 0,
                onExit.getValidFrom(), onExit.getValidTo(), onExit.getFaceSha256()));

        DeviceReviewItem second = openReview(exitId, asha);
        assertThat(second.getPublicId()).isNotEqualTo(first.getPublicId());
        DeviceReviewItem firstAfter = deviceReviewItemRepository.findByPublicId(first.getPublicId()).orElseThrow();
        assertThat(firstAfter.isResolved()).as("the entrance decision is not reopened").isTrue();
        assertThat(firstAfter.getDecision()).isEqualTo("ACCEPT_SERVER");

        JsonNode open = getJson("/api/v1/reviews");
        assertThat(open).singleElement().satisfies(item -> {
            assertThat(item.get("id").asString()).isEqualTo(second.getPublicId());
            assertThat(item.get("memberId").asString()).isEqualTo(ashaId);
            assertThat(item.get("memberName").asString()).isEqualTo("Asha Shah");
            assertThat(item.get("deviceName").asString()).isEqualTo("Exit");
        });
    }


    @Test
    void aReplayedObservationFromBeforeTheImportOpensNothing() throws Exception {
        String face = uploadFace(Color.RED);
        String original = person("11", "Asha Rao", 0, ASHA_FROM, ASHA_TO, face);
        trustedRoster(entranceId, original);
        importPending(entranceId);
        ackEverything(entranceId);
        long members = memberRepository.count();

        readerSays(entranceId, original);

        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(enrollment(entranceId, "11").isResolved()).isTrue();
        assertThat(enrollment(entranceId, "11").getDecision()).isEqualTo("CREATE");
        assertThat(deviceReviewItemRepository.count()).isZero();
        assertThat(getJson("/api/v1/reviews")).isEmpty();
    }

    /**
     * A limitation, kept visible: an undecided person the server has not heard about yet cannot hold
     * a seed back. When their delayed observation arrives the server neither links nor overwrites
     * them; they are pending, flagged as the seeded member, and an import leaves them pending.
     */
    @Test
    void aDelayedObservationOfAPossibleDuplicateAfterASeedIsFlaggedAndNeverLinked() throws Exception {
        Member om = onlyOn(entranceId, "Om", "Shah", "ID-OM", "7101");
        trustedRoster(exitId);
        DesiredMemberProjection seeded = projection(exitId, om);
        ackEverything(exitId);

        readerSays(exitId, person("90", "Om Shah", 0, OTHER_FROM, OTHER_TO, uploadFace(Color.GREEN)));

        PendingEnrollment late = enrollment(exitId, "90");
        assertThat(late.isResolved()).isFalse();
        assertThat(openItem(late.getPublicId()).get("possibleMemberId").asString()).isEqualTo(om.getPublicId());
        assertThat(projection(exitId, om).getDeviceUserId()).isEqualTo(seeded.getDeviceUserId());
        assertThat(projection(exitId, om).getRevision()).isEqualTo(seeded.getRevision());
        assertThat(importPending(exitId).get("created").asInt()).isZero();
        assertThat(memberRepository.count()).isEqualTo(1);
    }


    private JsonNode importPending(String readerPublicId) throws Exception {
        return readJson(postJson("/api/v1/devices/" + readerPublicId + "/import-pending", "{}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode openItem(String id) throws Exception {
        for (JsonNode item : getJson("/api/v1/reviews")) {
            if (id.equals(item.get("id").asString())) {
                return item;
            }
        }
        throw new AssertionError("not listed: " + id);
    }

    private DeviceReviewItem openReview(String readerPublicId, Member member) {
        return deviceReviewItemRepository.findByDeviceId(readerPk(readerPublicId)).stream()
                .filter(item -> item.getMemberId().equals(member.getId()) && !item.isResolved())
                .findFirst()
                .orElseThrow();
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

    private <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }
}
