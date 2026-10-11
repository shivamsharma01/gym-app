package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.AccessDirection;
import com.example.gym.device.domain.AccessResult;
import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * V19: the gateway polls one time window per reader and posts what it read. A repeated window stores
 * nothing new, an empty window writes nothing, an unmapped punch is stored without a member, the
 * cursor only moves forward, and no member or mapping row changes.
 */
class V19AttendancePollIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String gatewayToken;
    private String entranceId;
    private Long entrance;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Poll Gym", "poll-gym");
        createUser(tenant.getId(), "poll-admin", "poll-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("poll-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayId, gateway.get("token").asString());
        entranceId = createReader("Entrance", gatewayId);
        entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
    }

    @Test
    void aRepeatedWindowInsertsNoDuplicate() throws Exception {
        String punches = """
                [{"deviceUserId":"7","occurredAt":"2026-10-11T05:00:00Z","method":"FACE","granted":true,"recNo":10},
                 {"deviceUserId":"8","occurredAt":"2026-10-11T05:01:00Z","method":"FACE","granted":true,"recNo":11}]""";

        JsonNode first = window(entranceId, punches);
        JsonNode again = window(entranceId, punches);

        assertThat(first.get("stored").asInt()).isEqualTo(2);
        assertThat(again.get("stored").asInt()).isZero();
        assertThat(again.get("lastEventAt").asString()).isEqualTo("2026-10-11T05:01:00Z");
        assertThat(attendanceEventRepository.findAll()).hasSize(2);
        assertThat(securityEventRepository.findAll()).hasSize(2);
    }

    @Test
    void anEmptyWindowWritesNothing() throws Exception {
        JsonNode result = window(entranceId, "[]");

        assertThat(result.get("stored").asInt()).isZero();
        assertThat(result.get("lastEventAt").isNull()).isTrue();
        assertThat(attendanceEventRepository.findAll()).isEmpty();
        assertThat(attendanceSyncCursorRepository.findAll()).isEmpty();
        assertThat(securityEventRepository.findAll()).isEmpty();
    }

    @Test
    void aPunchByAnUnmappedIdIsStoredUnlinked() throws Exception {
        window(entranceId, """
                [{"deviceUserId":"9100","occurredAt":"2026-10-11T06:00:00Z","method":"FACE","granted":true,"recNo":5}]""");

        assertThat(attendanceEventRepository.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getMemberId()).isNull();
            assertThat(e.getDeviceUserId()).isEqualTo("9100");
            assertThat(e.getOccurredAt()).isEqualTo(Instant.parse("2026-10-11T06:00:00Z"));
        });
        assertThat(memberRepository.findAll()).isEmpty();
        assertThat(memberDeviceMappingRepository.findAll()).isEmpty();
        assertThat(securityEventRepository.findAll()).singleElement()
                .satisfies(s -> assertThat(s.getType()).isEqualTo("UNKNOWN_CREDENTIAL"));
    }

    @Test
    void noMemberOrMappingRowChangesDuringIngest() throws Exception {
        String publicId = readJson(createMemberWithFace(token,
                "{\"firstName\":\"Asha\",\"serialNumber\":\"1901\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        Member asha = memberRepository.findByPublicId(publicId).orElseThrow();
        MemberDeviceMapping mapping = memberDeviceMappingRepository
                .findByDeviceIdAndMemberId(entrance, asha.getId()).orElseThrow();
        List<String> membersBefore = memberRows();
        List<String> mappingsBefore = mappingRows();
        List<String> desiredBefore = desiredRows();

        window(entranceId, """
                [{"deviceUserId":"%s","occurredAt":"2026-10-11T07:00:00Z","method":"FACE","granted":true,"recNo":1},
                 {"deviceUserId":"%s","occurredAt":"2026-10-11T07:05:00Z","method":"FACE","granted":false,
                  "recNo":2,"errorCode":20},
                 {"deviceUserId":"4444","occurredAt":"2026-10-11T07:06:00Z","method":"FACE","granted":true,"recNo":3}]"""
                .formatted(mapping.getDeviceUserId(), mapping.getDeviceUserId()));

        assertThat(memberRows()).isEqualTo(membersBefore);
        assertThat(mappingRows()).isEqualTo(mappingsBefore);
        assertThat(desiredRows()).isEqualTo(desiredBefore);
        assertThat(attendanceEventRepository.findAll()).hasSize(3)
                .filteredOn(e -> mapping.getDeviceUserId().equals(e.getDeviceUserId()))
                .allMatch(e -> asha.getId().equals(e.getMemberId()));
        assertThat(attendanceEventRepository.findAll())
                .filteredOn(e -> e.getResult() == AccessResult.DENIED)
                .singleElement()
                .satisfies(e -> assertThat(e.getDenyReason()).isEqualTo("VALIDITY_PERIOD"));
    }

    @Test
    void theCursorMovesOnlyForward() throws Exception {
        assertThat(cursor(entranceId).get("lastEventAt").isNull()).isTrue();

        window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"2026-10-11T08:00:00Z","method":"FACE","granted":true,"recNo":20}]""");
        JsonNode older = window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"2026-10-11T07:00:00Z","method":"FACE","granted":true,"recNo":19}]""");

        assertThat(older.get("stored").asInt()).isEqualTo(1);
        assertThat(older.get("lastEventAt").asString()).isEqualTo("2026-10-11T08:00:00Z");
        assertThat(cursor(entranceId).get("lastEventAt").asString()).isEqualTo("2026-10-11T08:00:00Z");
        mockMvc.perform(get("/api/v1/devices/" + entranceId + "/health").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastPunchAt").value("2026-10-11T08:00:00Z"));
    }

    @Test
    void aFutureDatedPunchIsStoredButDoesNotMoveTheCursorSoLaterPunchesAreNotSkipped() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant real = now.minus(10, ChronoUnit.MINUTES);
        Instant future = now.plus(1, ChronoUnit.DAYS);

        JsonNode first = window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"%s","method":"FACE","granted":true,"recNo":1},
                 {"deviceUserId":"7","occurredAt":"%s","method":"FACE","granted":true,"recNo":2}]"""
                .formatted(real, future));

        assertThat(first.get("stored").asInt()).isEqualTo(2);
        assertThat(Instant.parse(first.get("lastEventAt").asString())).isEqualTo(real);
        assertThat(Instant.parse(cursor(entranceId).get("lastEventAt").asString())).isEqualTo(real);

        Instant later = now.minus(5, ChronoUnit.MINUTES);
        JsonNode next = window(entranceId, """
                [{"deviceUserId":"8","occurredAt":"%s","method":"FACE","granted":true,"recNo":3}]""".formatted(later));

        assertThat(next.get("stored").asInt()).isEqualTo(1);
        assertThat(Instant.parse(next.get("lastEventAt").asString())).isEqualTo(later);
        assertThat(attendanceEventRepository.findAll()).hasSize(3);
    }

    @Test
    void aWindowOfOnlyFutureDatedPunchesLeavesTheCursorWhereItWas() throws Exception {
        Instant future = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        JsonNode result = window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"%s","method":"FACE","granted":true,"recNo":1}]""".formatted(future));

        assertThat(result.get("stored").asInt()).isEqualTo(1);
        assertThat(result.get("lastEventAt").isNull()).isTrue();
        assertThat(attendanceSyncCursorRepository.findAll()).isEmpty();
    }

    @Test
    void aBatchOfFiveHundredIsStoredAndALargerOneIsRefusedWithoutWriting() throws Exception {
        Instant start = Instant.parse("2026-10-11T00:00:00Z");
        JsonNode stored = window(entranceId, punches(start, 0, 500));

        assertThat(stored.get("stored").asInt()).isEqualTo(500);
        assertThat(Instant.parse(stored.get("lastEventAt").asString())).isEqualTo(start.plusSeconds(499));

        mockMvc.perform(post("/internal/gateway/attendance")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + entranceId + "\",\"punches\":" + punches(start, 500, 501) + "}"))
                .andExpect(status().isBadRequest());

        assertThat(attendanceEventRepository.count()).isEqualTo(500);
        assertThat(Instant.parse(cursor(entranceId).get("lastEventAt").asString())).isEqualTo(start.plusSeconds(499));
    }

    @Test
    void aRecordNumberSeenAgainAtAnotherTimeIsANewPunch() throws Exception {
        window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"2026-10-11T05:00:00Z","method":"FACE","granted":true,"recNo":1}]""");
        JsonNode afterReboot = window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"2026-10-11T09:00:00Z","method":"FACE","granted":true,"recNo":1}]""");

        assertThat(afterReboot.get("stored").asInt()).isEqualTo(1);
        assertThat(attendanceEventRepository.findAll()).hasSize(2);
    }

    @Test
    void aPunchStoredBeforeThePollIsNotStoredAgain() throws Exception {
        Instant at = Instant.parse("2026-10-11T04:00:00Z");
        attendanceEventRepository.save(new AttendanceEvent(tenant.getId(), entrance, null, "7", at,
                AccessDirection.IN, "FACE", AccessResult.GRANTED, 77L, "rec:77"));
        attendanceEventRepository.save(new AttendanceEvent(tenant.getId(), entrance, null, "8", at,
                AccessDirection.IN, "FACE", AccessResult.GRANTED, null, "cmp:8|" + at.toEpochMilli() + "|FACE|G"));

        JsonNode result = window(entranceId, """
                [{"deviceUserId":"7","occurredAt":"2026-10-11T04:00:00Z","method":"FACE","granted":true,"recNo":77},
                 {"deviceUserId":"8","occurredAt":"2026-10-11T04:00:00Z","method":"FACE","granted":true,"recNo":78}]""");

        assertThat(result.get("stored").asInt()).isZero();
        assertThat(attendanceEventRepository.findAll()).hasSize(2);
    }

    @Test
    void aPunchAppearsOnceInTheAttendanceList() throws Exception {
        String punch = """
                [{"deviceUserId":"9200","occurredAt":"2026-10-11T10:00:00Z","method":"FACE","granted":true,"recNo":3}]""";
        window(entranceId, punch);
        window(entranceId, punch);

        mockMvc.perform(get("/api/v1/attendance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].deviceUserId").value("9200"))
                .andExpect(jsonPath("$.content[0].memberLinked").value(false));
    }

    @Test
    void aLiveAccessEventIsNotAnAttendanceSource() {
        String reply = gatewayMessageService.process("""
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s","type":"DEVICE_EVENT",
                 "correlationId":null,"payload":{"deviceUserId":"7","occurredAt":"2026-10-11T05:00:00Z",
                 "method":"FACE","granted":true,"recNo":10}}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, entranceId), gatewayId).orElseThrow();

        assertThat(reply).contains("ACK");
        assertThat(attendanceEventRepository.findAll()).isEmpty();
        assertThat(attendanceSyncCursorRepository.findAll()).isEmpty();
    }

    @Test
    void onlyTheOwningGatewayMayPostAWindow() throws Exception {
        Tenant otherGym = createTenant("Other Gym", "other-gym");
        createUser(otherGym.getId(), "other-admin", "other-admin@gym.local", "GYM_ADMIN");
        String otherAdmin = tokenFor("other-admin");
        JsonNode other = readJson(mockMvc.perform(post("/api/v1/gateways").header("Authorization", "Bearer " + otherAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Other\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String otherToken = enroll(other.get("id").asString(), other.get("token").asString());
        String body = "{\"deviceId\":\"" + entranceId + "\",\"punches\":[{\"deviceUserId\":\"7\","
                + "\"occurredAt\":\"2026-10-11T05:00:00Z\",\"method\":\"FACE\",\"granted\":true}]}";

        mockMvc.perform(post("/internal/gateway/attendance").header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/internal/gateway/attendance")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(attendanceEventRepository.findAll()).isEmpty();
    }

    private List<String> memberRows() {
        return memberRepository.findAll().stream()
                .map(m -> m.getId() + "|" + m.getVersion() + "|" + m.getUpdatedAt() + "|" + m.getStatus())
                .sorted().toList();
    }

    private List<String> mappingRows() {
        return memberDeviceMappingRepository.findAll().stream()
                .map(m -> m.getId() + "|" + m.getVersion() + "|" + m.getUpdatedAt() + "|" + m.getMemberId()
                        + "|" + m.getDeviceUserId())
                .sorted().toList();
    }

    private List<String> desiredRows() {
        return desiredMemberProjectionRepository.findAll().stream()
                .map(d -> d.getId() + "|" + d.getVersion() + "|" + d.getRevision())
                .sorted().toList();
    }

    /** One punch per second from {@code start + first} seconds, {@code count} punches. */
    private static String punches(Instant start, int first, int count) {
        return IntStream.range(first, first + count)
                .mapToObj(i -> "{\"deviceUserId\":\"" + (1000 + i) + "\",\"occurredAt\":\"" + start.plusSeconds(i)
                        + "\",\"method\":\"FACE\",\"granted\":true,\"recNo\":" + i + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private JsonNode window(String devicePublicId, String punches) throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/attendance")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + devicePublicId + "\",\"punches\":" + punches + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode cursor(String devicePublicId) throws Exception {
        return readJson(mockMvc.perform(get("/internal/gateway/attendance/cursor")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", devicePublicId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String createReader(String name, String gatewayPublicId) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.30\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayPublicId + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private String enroll(String gateway, String enrollmentToken) throws Exception {
        return readJson(mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gateway + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("credential").asString();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
