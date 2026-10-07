package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.live.StaffLiveBroadcast;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Attendance is credited through the reader's own mapping (device + device user id). A punch that
 * arrives before its reader user is mapped is linked once the reader's user is mapped; a linked
 * punch is never moved to another member.
 */
@RecordApplicationEvents
class AttendanceLinkingIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents applicationEvents;

    private final AtomicLong recNo = new AtomicLong(1);

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String entranceId;
    private String exitId;
    private Long entrance;
    private Long exit;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Link Gym", "link-gym");
        createUser(tenant.getId(), "link-admin", "link-admin@link.local", "GYM_ADMIN");
        token = tokenFor("link-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN-link\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayId = readJson(createdGateway).get("id").asString();
        String enrollmentToken = readJson(createdGateway).get("token").asString();
        mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk());

        entranceId = createDevice("Entrance", "ENTRANCE", "10.0.0.20");
        exitId = createDevice("Exit", "EXIT", "10.0.0.21");
        entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        exit = deviceRepository.findByPublicId(exitId).orElseThrow().getId();
    }

    @Test
    void reconcileMapsNewReaderUsersBeforeStoringTheirPunches() throws Exception {
        gatewayMessageService.process(envelope(entranceId, "RECONCILIATION_RESULT", """
                {"ok":true,
                 "deviceUsers":[{"deviceUserId":"1114","name":"Ravi Kumar","frozen":false}],
                 "events":[{"deviceUserId":"1114","occurredAt":"2026-10-02T06:00:00Z",
                            "method":"FACE","granted":true,"recNo":501}]}
                """), gatewayId);

        Member ravi = memberBySerial("1114");
        assertThat(attendanceEventRepository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(ravi.getId()));
        assertThat(securityEventRepository.findAll())
                .noneMatch(s -> "UNKNOWN_CREDENTIAL".equals(s.getType()));
        mockMvc.perform(get("/api/v1/attendance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].memberLinked").value(true))
                .andExpect(jsonPath("$.content[0].memberName").value("Ravi Kumar"));
        // Credited on insert, so there was nothing to relink.
        assertThat(linkedBroadcasts()).isZero();
    }

    @Test
    void aPunchBeforeTheMappingIsLinkedWhenTheReaderReportsTheUser() throws Exception {
        punch(entranceId, "792");
        punch(entranceId, "792");
        punch(exitId, "792");
        assertThat(attendanceEventRepository.findAll()).hasSize(3)
                .allMatch(e -> e.getMemberId() == null);
        mockMvc.perform(get("/api/v1/attendance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].memberLinked").value(false))
                .andExpect(jsonPath("$.content[0].memberName").doesNotExist());

        gatewayMessageService.process(envelope(entranceId, "DEVICE_USER_CHANGED", newReaderUser("792", "Meera Shah")), gatewayId);

        Member meera = memberBySerial("792");
        assertThat(eventsOn(entrance, "792")).hasSize(2)
                .allMatch(e -> meera.getId().equals(e.getMemberId()));
        // The exit reader only got Meera from the server, so its earlier punch is not claimed.
        assertThat(eventsOn(exit, "792")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isNull());
        mockMvc.perform(get("/api/v1/members/" + meera.getPublicId() + "/attendance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].memberName").value("Meera Shah"));
        // Two rows linked in one transaction: one refresh for the UI.
        assertThat(linkedBroadcasts()).isEqualTo(1);
    }

    @Test
    void aLinkedPunchIsNeverMovedToAnotherMember() throws Exception {
        Member asha = createMember("Asha", "1001");
        Member bina = createMember("Bina", "1002");
        punch(entranceId, "1001");
        AttendanceEvent event = attendanceEventRepository.findAll().getFirst();
        assertThat(event.getMemberId()).isEqualTo(asha.getId());

        Integer changed = new TransactionTemplate(transactionManager).execute(status ->
                attendanceEventRepository.linkUnmatched(tenant.getId(), entrance, "1001", bina.getId()));

        assertThat(changed).isZero();
        assertThat(attendanceEventRepository.findById(event.getId()).orElseThrow().getMemberId())
                .isEqualTo(asha.getId());
    }

    @Test
    void theSameDeviceUserIdOnTwoReadersStaysWithEachReadersMember() throws Exception {
        Member asha = createMember("Asha", "6001");
        Member bina = createMember("Bina", "6002");
        // The exit reader holds Bina under 6001 and does not hold Asha.
        memberDeviceMappingRepository.delete(
                memberDeviceMappingRepository.findByDeviceIdAndMemberId(exit, asha.getId()).orElseThrow());
        var binaOnExit = memberDeviceMappingRepository.findByDeviceIdAndMemberId(exit, bina.getId()).orElseThrow();
        binaOnExit.setDeviceUserId("6001");
        memberDeviceMappingRepository.save(binaOnExit);

        punch(entranceId, "6001");
        punch(exitId, "6001");
        punch(entranceId, "8001");
        punch(exitId, "8001");

        assertThat(eventsOn(entrance, "6001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(asha.getId()));
        assertThat(eventsOn(exit, "6001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(bina.getId()));

        gatewayMessageService.process(envelope(entranceId, "DEVICE_USER_CHANGED", newReaderUser("8001", "Esha Jain")), gatewayId);

        Member esha = memberBySerial("8001");
        assertThat(eventsOn(entrance, "8001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(esha.getId()));
        assertThat(eventsOn(exit, "8001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isNull());
        assertThat(eventsOn(exit, "6001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(bina.getId()));
    }

    @Test
    void changingTheSerialKeepsEarlierAttendanceWithTheMember() throws Exception {
        Member asha = createMember("Asha", "1001");
        punch(entranceId, "1001");
        punch(exitId, "1001");

        moveSerial(asha, "7");
        Member bina = createMember("Bina", "1001");
        punch(entranceId, "1001");
        punch(entranceId, "7");

        List<AttendanceEvent> under1001 = eventsOn(entrance, "1001");
        assertThat(under1001).hasSize(2);
        assertThat(under1001.get(0).getMemberId()).isEqualTo(asha.getId());
        assertThat(under1001.get(1).getMemberId()).isEqualTo(bina.getId());
        assertThat(eventsOn(exit, "1001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(asha.getId()));
        assertThat(eventsOn(entrance, "7")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(asha.getId()));
        mockMvc.perform(get("/api/v1/members/" + asha.getPublicId() + "/attendance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void aReaderUserCreatedUnderTheOldIdDoesNotTakeOverItsHistory() throws Exception {
        Member asha = createMember("Asha", "1001");
        punch(entranceId, "1001");
        moveSerial(asha, "7");

        gatewayMessageService.process(envelope(entranceId, "DEVICE_USER_CHANGED", newReaderUser("1001", "Kiran Das")), gatewayId);

        Member kiran = memberBySerial("1001");
        assertThat(kiran.getId()).isNotEqualTo(asha.getId());
        assertThat(eventsOn(entrance, "1001")).singleElement()
                .satisfies(e -> assertThat(e.getMemberId()).isEqualTo(asha.getId()));
        assertThat(linkedBroadcasts()).isZero();
    }

    @Test
    void attendanceIsOrderedByTimeThenStoredOrder() throws Exception {
        String sameTime = "2026-10-02T07:00:00Z";
        for (String user : List.of("a1", "a2", "a3")) {
            long n = recNo.getAndIncrement();
            gatewayMessageService.process(envelope(entranceId, "DEVICE_EVENT", """
                    {"deviceUserId":"%s","occurredAt":"%s","method":"FACE","granted":true,"recNo":%d}
                    """.formatted(user, sameTime, n)), gatewayId);
        }
        punch(entranceId, "early");

        assertThat(listedUsers("desc")).containsExactly("a3", "a2", "a1", "early");
        assertThat(listedUsers("asc")).containsExactly("early", "a1", "a2", "a3");
    }

    private List<String> listedUsers(String direction) throws Exception {
        String body = mockMvc.perform(get("/api/v1/attendance").param("direction", direction)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> users = new java.util.ArrayList<>();
        readJson(body).get("content").forEach(e -> users.add(e.get("deviceUserId").asString()));
        return users;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void moveSerial(Member member, String serial) throws Exception {
        mockMvc.perform(put("/api/v1/members/" + member.getPublicId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + member.getFirstName() + "\",\"serialNumber\":\"" + serial + "\"}"))
                .andExpect(status().isOk());
        var creates = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> c.getType() == SyncCommandType.CREATE_USER
                        && c.getState() != SyncCommandState.CANCELLED
                        && c.getPayload().contains("\"deviceUserId\":\"" + serial + "\""))
                .toList();
        assertThat(creates).hasSize(2);
        for (var create : creates) {
            String deviceId = create.getDeviceId().equals(entrance) ? entranceId : exitId;
            gatewayMessageService.process(envelope(deviceId, "SYNC_RESULT", create.getCorrelationId(), "{\"ok\":true}"), gatewayId);
        }
        assertThat(memberDeviceMappingRepository.findByMemberId(member.getId()))
                .allMatch(m -> serial.equals(m.getDeviceUserId()) && m.getPendingDeviceUserId() == null);
    }

    private Member createMember(String firstName, String serial) throws Exception {
        String id = readJson(postJson("/api/v1/members",
                "{\"firstName\":\"" + firstName + "\",\"serialNumber\":\"" + serial + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        return memberRepository.findByPublicId(id).orElseThrow();
    }

    private Member memberBySerial(String serial) {
        return memberRepository.findByTenantIdAndSerialNumber(tenant.getId(), serial).orElseThrow();
    }

    private List<AttendanceEvent> eventsOn(Long deviceId, String deviceUserId) {
        return attendanceEventRepository.findAll().stream()
                .filter(e -> e.getDeviceId().equals(deviceId) && deviceUserId.equals(e.getDeviceUserId()))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
    }

    private long linkedBroadcasts() {
        return applicationEvents.stream(StaffLiveBroadcast.class)
                .filter(b -> "ATTENDANCE_LINKED".equals(b.type()))
                .count();
    }

    private void punch(String devicePublicId, String deviceUserId) {
        long n = recNo.getAndIncrement();
        gatewayMessageService.process(envelope(devicePublicId, "DEVICE_EVENT", """
                {"deviceUserId":"%s","occurredAt":"%s","method":"FACE","granted":true,"recNo":%d}
                """.formatted(deviceUserId, Instant.parse("2026-10-02T05:00:00Z").plusSeconds(n), n)), gatewayId);
    }

    private static String newReaderUser(String deviceUserId, String name) {
        return """
                {"deviceUserId":"%s","name":"%s","frozen":false,"deviceChangedAt":"%s","isNew":true,
                 "profileChanged":true,"nameChanged":true,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":false,"faceRemoved":false}
                """.formatted(deviceUserId, name, Instant.now());
    }

    private String createDevice(String name, String role, String host) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"host\":\"" + host
                        + "\",\"port\":37777,\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            req = req.content(body);
        }
        return mockMvc.perform(req);
    }

    private String envelope(String devicePublicId, String type, String payloadJson) {
        return envelope(devicePublicId, type, UUID.randomUUID().toString(), payloadJson);
    }

    private String envelope(String devicePublicId, String type, String correlationId, String payloadJson) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId, type,
                correlationId, payloadJson);
    }
}
