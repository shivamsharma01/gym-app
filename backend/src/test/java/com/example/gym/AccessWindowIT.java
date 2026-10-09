package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.AccessCheckScheduler.AccessCheck;
import com.example.gym.device.DeviceAuthorizationService;
import com.example.gym.device.DeviceAuthorizationService.AccessWindow;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.GatewayProperties;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceReaderBaseline;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.member.Member;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * What a member's devices hold: one window (dates + enabled) taken from the running or next
 * membership, moved along by the hourly access check. Payment does not enable or disable.
 * A reader edit does not change the membership. It is stored for review.
 */
class AccessWindowIT extends AbstractIntegrationTest {

    @Autowired
    private DeviceAuthorizationService authorizationService;
    @Autowired
    private AccessCheck accessCheck;
    @Autowired
    private GatewayProperties gatewayProperties;
    @Autowired
    private GatewayMessageService gatewayMessageService;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private final LocalDate today = LocalDate.now();
    private String token;
    private String gatewayId;
    private String deviceId;
    private String planId;
    private String memberId;
    private Long memberDbId;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Window Gym", "window-gym");
        createUser(tenant.getId(), "win-admin", "win-admin@win.local", "GYM_ADMIN");
        token = tokenFor("win-admin");
        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN-win\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        gatewayId = readJson(createdGateway).get("id").asString();
        deviceId = readJson(postJson("/api/v1/devices",
                "{\"name\":\"Entrance\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.30\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        planId = readJson(postJson("/api/v1/plans",
                "{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        memberId = readJson(postJson("/api/v1/members",
                "{\"firstName\":\"Ria\",\"lastName\":\"Sen\",\"memberCode\":\"8001\",\"serialNumber\":\"8001\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        memberDbId = memberRepository.findByPublicId(memberId).orElseThrow().getId();
    }

    @Test
    void windowFollowsTheRunningThenTheNextMembershipAndGapsAreLeftToTheDevice() throws Exception {
        LocalDate jan1 = today.withDayOfYear(1).plusYears(1);
        String jan = membership(jan1, jan1.plusDays(30));
        String feb = membership(jan1.plusDays(35), jan1.plusDays(62));
        pay(jan);
        pay(feb);
        Member member = member();

        AccessWindow inJan = authorizationService.window(member, jan1.plusDays(10)).orElseThrow();
        assertThat(inJan.validFrom()).isEqualTo(jan1);
        assertThat(inJan.validTo()).isEqualTo(jan1.plusDays(30));
        assertThat(inJan.enabled()).isTrue();

        // Between the two: the next membership's dates, enabled; the device refuses entry until it starts.
        AccessWindow inGap = authorizationService.window(member, jan1.plusDays(32)).orElseThrow();
        assertThat(inGap.validFrom()).isEqualTo(jan1.plusDays(35));
        assertThat(inGap.validTo()).isEqualTo(jan1.plusDays(62));
        assertThat(inGap.enabled()).isTrue();

        // After both: last dates, disabled.
        AccessWindow after = authorizationService.window(member, jan1.plusDays(70)).orElseThrow();
        assertThat(after.validTo()).isEqualTo(jan1.plusDays(62));
        assertThat(after.enabled()).isFalse();

        // Devices that don't enforce dates: the not-yet-started membership waits disabled.
        gatewayProperties.setDevicesEnforceValidityDates(false);
        try {
            assertThat(authorizationService.window(member, jan1.plusDays(32)).orElseThrow().enabled()).isFalse();
            assertThat(authorizationService.window(member, jan1.plusDays(35)).orElseThrow().enabled()).isTrue();
        } finally {
            gatewayProperties.setDevicesEnforceValidityDates(true);
        }
    }

    @Test
    void backToBackMembershipsBecomeOneWindowWhetherOrNotTheyArePaid() throws Exception {
        LocalDate start = today.minusDays(10);
        membership(start, start.plusDays(29));
        membership(start.plusDays(30), start.plusDays(59));
        membership(start.plusDays(60), start.plusDays(89));

        AccessWindow w = authorizationService.window(member()).orElseThrow();
        assertThat(w.validFrom()).isEqualTo(start);
        assertThat(w.validTo()).isEqualTo(start.plusDays(89));
        assertThat(w.enabled()).isTrue();
    }

    @Test
    void addingAFutureMembershipDoesNotTouchTheDevicesDuringTheRunningOne() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        pay(current);
        assertThat(accessCommandsSince(0)).isEmpty();
        assertThat(member().getDeviceEnabled()).isTrue();

        long before = deviceSyncCommandRepository.count();
        membership(today.plusDays(40), today.plusDays(70));
        assertThat(accessCommandsSince(before)).isEmpty();
        assertThat(member().getDeviceEnabled()).isTrue();
    }

    @Test
    void accessCheckMovesToTheNextMembershipEvenWhenItIsUnpaid() throws Exception {
        String ended = membership(today.minusDays(31), today.minusDays(1));
        pay(ended);
        String next = membership(today.plusDays(3), today.plusDays(33));
        // Simulate: the devices still hold the membership that ended yesterday.
        Member member = member();
        member.setDeviceWindow(today.minusDays(31), today.minusDays(1), true);
        memberRepository.save(member);
        mapMember();

        long before = deviceSyncCommandRepository.count();
        assertThat(accessCheck.run()).isEqualTo(1);
        assertThat(accessCommandsSince(before)).isEmpty();
        assertThat(member().getDeviceValidFrom()).isEqualTo(today.plusDays(3));
        assertThat(member().getDeviceValidTo()).isEqualTo(today.plusDays(33));
        assertThat(member().getDeviceEnabled()).isTrue();
        assertThat(member().getAccessChangedAt()).isAfter(Instant.now().minusSeconds(60));

        // Running it again changes nothing.
        before = deviceSyncCommandRepository.count();
        assertThat(accessCheck.run()).isZero();
        assertThat(accessCommandsSince(before)).isEmpty();

        // Recording a payment does not send another access command.
        before = deviceSyncCommandRepository.count();
        pay(next);
        assertThat(accessCommandsSince(before)).isEmpty();
    }

    @Test
    void lastMembershipEndedWithoutANextOneDisablesAndKeepsTheDates() throws Exception {
        String ended = membership(today.minusDays(31), today.minusDays(1));
        pay(ended);
        Member member = member();
        member.setDeviceWindow(today.minusDays(31), today.minusDays(1), true);
        memberRepository.save(member);
        mapMember();

        long before = deviceSyncCommandRepository.count();
        accessCheck.run();
        assertThat(accessCommandsSince(before)).isEmpty();
        assertThat(member().getDeviceEnabled()).isFalse();
        assertThat(member().getDeviceValidTo()).isEqualTo(today.minusDays(1));
        assertThat(member().getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void enablingAnUnpaidMemberOnTheDeviceIsAccepted() throws Exception {
        membership(today.minusDays(5), today.plusDays(25));
        long before = deviceSyncCommandRepository.count();

        deviceUserChanged(access(false, today.minusDays(5), today.plusDays(25), true, false));

        assertThat(accessCommandsSince(before))
                .noneMatch(c -> c.getType() == SyncCommandType.DISABLE_USER);
        assertThat(auditLogRepository.findAll()).noneMatch(a ->
                a.getDetails() != null && a.getDetails().contains("membership is unpaid"));
        assertThat(authorizationService.window(member()).orElseThrow().enabled()).isTrue();
    }

    @Test
    void datesEditedOnTheDeviceAreAppliedEvenWhenTheyOverlapTheNextMembership() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(10));
        String next = membership(today.plusDays(15), today.plusDays(45));
        pay(current);
        pay(next);

        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(10));
        deviceUserChanged(access(false, today.minusDays(5), today.plusDays(20), false, true));

        Membership edited = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(edited.getEndDate()).isEqualTo(today.plusDays(10));
        assertThat(deviceReviewItemRepository.findAll()).singleElement()
                .extracting(DeviceReviewItem::getDecision, DeviceReviewItem::isResolved)
                .containsExactly(null, false);
        assertThat(membershipRepository.findByPublicIdAndDeletedFalse(next).orElseThrow().getStartDate())
                .isEqualTo(today.plusDays(15));
        assertThat(reconciliationConflictRepository.findAll()).isEmpty();
        Long device = deviceRepository.findByPublicId(deviceId).orElseThrow().getId();
        assertThat(deviceObservedUserRepository.findByDeviceIdAndDeviceUserId(device, "8001").orElseThrow()
                .getValidTo()).contains(today.plusDays(20).toString());
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    @Test
    void datesEditedOnTheDeviceApplyToAFrozenMembershipToo() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        pay(current);
        postJson("/api/v1/memberships/" + current + "/freeze", null).andExpect(status().isOk());

        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));
        deviceUserChanged(access(true, today.minusDays(5), today.plusDays(40), false, true));

        Membership m = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(m.getEndDate()).isEqualTo(today.plusDays(25));
        assertThat(deviceReviewItemRepository.findAll()).singleElement()
                .extracting(DeviceReviewItem::getDecision)
                .isNull();
        assertThat(m.getStatus()).isEqualTo(MembershipStatus.FROZEN);
        Long device = deviceRepository.findByPublicId(deviceId).orElseThrow().getId();
        assertThat(deviceObservedUserRepository.findByDeviceIdAndDeviceUserId(device, "8001").orElseThrow()
                .getValidTo()).contains(today.plusDays(40).toString());
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    @Test
    void aStartDateOnlyDifferenceOpensAReviewAndLeavesTheMembership() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));

        deviceUserChanged(access(false, today.minusDays(1), today.plusDays(25), false, true));

        Membership stored = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(stored.getStartDate()).isEqualTo(today.minusDays(5));
        assertThat(stored.getEndDate()).isEqualTo(today.plusDays(25));
        DeviceReviewItem item = deviceReviewItemRepository.findAll().getFirst();
        assertThat(item.getDecision()).isNull();
        assertThat(item.getReaderValidFrom()).contains(today.minusDays(1).toString());
        assertThat(item.getServerValidFrom()).contains(today.minusDays(5).toString());
        assertThat(item.getReaderValidTo()).contains(today.plusDays(25).toString());
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    @Test
    void anEndDateOnlyDifferenceOpensAReviewAndLeavesTheMembership() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));

        deviceUserChanged(access(false, today.minusDays(5), today.plusDays(40), false, true));

        Membership stored = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(stored.getStartDate()).isEqualTo(today.minusDays(5));
        assertThat(stored.getEndDate()).isEqualTo(today.plusDays(25));
        DeviceReviewItem item = deviceReviewItemRepository.findAll().getFirst();
        assertThat(item.getDecision()).isNull();
        assertThat(item.getReaderValidTo()).contains(today.plusDays(40).toString());
        assertThat(item.getServerValidTo()).contains(today.plusDays(25).toString());
        assertThat(item.getReaderValidFrom()).contains(today.minusDays(5).toString());
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    @Test
    void bothValidityDatesDifferingOpenOneReviewWithoutChoosingEitherSide() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));

        deviceUserChanged(access(false, today.plusDays(1), today.plusDays(60), false, true));

        Membership stored = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(stored.getStartDate()).isEqualTo(today.minusDays(5));
        assertThat(stored.getEndDate()).isEqualTo(today.plusDays(25));
        assertThat(deviceReviewItemRepository.findAll()).singleElement()
                .extracting(DeviceReviewItem::getDecision, DeviceReviewItem::getReaderValidFrom,
                        DeviceReviewItem::getReaderValidTo)
                .containsExactly(null, today.plusDays(1).toString(), today.plusDays(60).toString());
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    @Test
    void theSameCalendarDayDoesNotOpenAReview() throws Exception {
        membership(today.minusDays(5), today.plusDays(25));
        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));

        deviceUserChanged(access(false, today.minusDays(5), today.plusDays(25), false, true));

        assertThat(deviceReviewItemRepository.findAll()).isEmpty();
    }

    @Test
    void aReaderThatStillMatchesItsBaselineIsNotOverwrittenOrReviewed() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        DeviceReaderBaseline baseline = new DeviceReaderBaseline(device.getTenantId(), device.getId(), "8001");
        baseline.setReaderName("Ria");
        baseline.setReaderNameEx("Ria Sen");
        baseline.setAuthority("Customer");
        baseline.setValidFrom(today.plusDays(2) + "T00:00:00+05:30");
        baseline.setValidTo(today.plusDays(40) + "T23:59:59+05:30");
        deviceReaderBaselineRepository.save(baseline);

        deviceUserChanged(access(false, today.plusDays(2), today.plusDays(40), false, true));

        Membership stored = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(stored.getStartDate()).isEqualTo(today.minusDays(5));
        assertThat(stored.getEndDate()).isEqualTo(today.plusDays(25));
        assertThat(deviceReviewItemRepository.findAll()).isEmpty();
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    @Test
    void aZuluInstantIsComparedOnTheReaderCalendarDay() throws Exception {
        String current = membership(today.minusDays(5), today.plusDays(25));
        mapMember();
        seedDesired(today.minusDays(5), today.plusDays(25));
        String start = today.minusDays(6) + "T18:30:00Z";
        String end = today.plusDays(25) + "T18:29:59Z";

        deviceUserChanged(dated(start, end));

        assertThat(deviceReviewItemRepository.findAll()).isEmpty();

        deviceUserChanged(dated(today.minusDays(6) + "T18:29:59Z", end));

        Membership stored = membershipRepository.findByPublicIdAndDeletedFalse(current).orElseThrow();
        assertThat(stored.getStartDate()).isEqualTo(today.minusDays(5));
        assertThat(stored.getEndDate()).isEqualTo(today.plusDays(25));
        assertThat(deviceReviewItemRepository.findAll()).singleElement()
                .extracting(DeviceReviewItem::getReaderValidFrom)
                .isEqualTo(today.minusDays(6) + "T18:29:59Z");
        assertThat(accessCommandsSince(0)).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void seedDesired(LocalDate from, LocalDate to) {
        var device = deviceRepository.findByPublicId(deviceId).orElseThrow();
        Member member = member();
        DesiredMemberProjection row = new DesiredMemberProjection(member.getTenantId(), device.getId(), member.getId());
        row.setRevision(1);
        row.setDeviceUserId("8001");
        row.setPresentOnReader(true);
        row.setReaderName("Ria");
        row.setReaderNameEx("Ria Sen");
        row.setUserStatus(0);
        row.setValidFrom(from + "T00:00:00+05:30");
        row.setValidTo(to + "T23:59:59+05:30");
        row.setAuthority("Customer");
        row.setDoorNum(1);
        row.setTimeSectionNum(1);
        row.setFaceSha256("0".repeat(64));
        desiredMemberProjectionRepository.save(row);
    }

    private void mapMember() {
        Long device = deviceRepository.findByPublicId(deviceId).orElseThrow().getId();
        Member member = member();
        memberDeviceMappingRepository.save(new com.example.gym.device.domain.MemberDeviceMapping(
                member.getTenantId(), member.getId(), device, "8001"));
    }

    private Member member() {
        return memberRepository.findById(memberDbId).orElseThrow();
    }

    private String membership(LocalDate start, LocalDate end) throws Exception {
        return readJson(postJson("/api/v1/memberships", "{\"memberId\":\"" + memberId + "\",\"planId\":\"" + planId
                + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private void pay(String membershipId) throws Exception {
        postJson("/api/v1/payments", "{\"memberId\":\"" + memberId + "\",\"membershipId\":\"" + membershipId
                + "\",\"amount\":1000.00,\"method\":\"CASH\"}")
                .andExpect(status().isCreated());
    }

    private List<DeviceSyncCommand> accessCommandsSince(long previousCount) {
        return deviceSyncCommandRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .skip(previousCount)
                .filter(c -> memberDbId.equals(c.getMemberId()))
                .filter(c -> c.getType() == SyncCommandType.UPDATE_VALIDITY || c.getType() == SyncCommandType.DISABLE_USER)
                .toList();
    }

    private DeviceSyncCommand lastAccessCommand() {
        List<DeviceSyncCommand> all = accessCommandsSince(0);
        return all.getLast();
    }

    private static DeviceSyncCommand single(List<DeviceSyncCommand> commands) {
        assertThat(commands).hasSize(1);
        return commands.getFirst();
    }

    private String access(boolean frozen, LocalDate from, LocalDate to, boolean frozenChanged, boolean validityChanged) {
        return """
                {"deviceUserId":"8001","name":"Ria Sen","frozen":%s,"validFrom":"%s","validTo":"%s",
                 "deviceChangedAt":"%s","isNew":false,"profileChanged":true,"nameChanged":false,
                 "frozenChanged":%s,"validityChanged":%s,"faceChanged":false,"faceRemoved":false}
                """.formatted(frozen, from, to, Instant.now(), frozenChanged, validityChanged);
    }

    private String dated(String from, String to) {
        return """
                {"deviceUserId":"8001","name":"Ria Sen","frozen":false,"validFrom":"%s","validTo":"%s",
                 "deviceChangedAt":"%s","isNew":false,"profileChanged":true,"nameChanged":false,
                 "frozenChanged":false,"validityChanged":true,"faceChanged":false,"faceRemoved":false}
                """.formatted(from, to, Instant.now());
    }

    private void deviceUserChanged(String payload) {
        gatewayMessageService.process("""
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, deviceId, UUID.randomUUID(), payload), gatewayId);
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }
}
