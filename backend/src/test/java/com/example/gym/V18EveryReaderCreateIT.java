package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.Member;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * V18: creating a member with a face publishes one desired user to every reader of the gym that
 * has a gateway. Each reader allocates its own id and has its own revision. One reader's failure
 * does not undo the member or the other readers.
 */
class V18EveryReaderCreateIT extends AbstractIntegrationTest {

    @Autowired
    private DesiredProjectionService desiredProjectionService;

    @Autowired
    private MemberFaceService memberFaceService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private Tenant tenant;
    private String token;
    private String gatewayId;
    private String gatewayToken;
    private String entranceId;
    private String exitId;
    private Long entrance;
    private Long exit;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        tenant = createTenant("Fan Gym", "fan-gym");
        createUser(tenant.getId(), "fan-admin", "fan-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("fan-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayId = gateway.get("id").asString();
        gatewayToken = enroll(gatewayId, gateway.get("token").asString());
        entranceId = createReader("Entrance", gatewayId);
        exitId = createReader("Exit", gatewayId);
        entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        exit = deviceRepository.findByPublicId(exitId).orElseThrow().getId();
    }

    @Test
    void bothReadersGetTheUserAndFaceWithIndependentIds() throws Exception {
        onlyOnEntrance("Ria", "V18-RIA", "1801");

        String publicId = create("Asha", "V18-ASHA", "1802");
        Member asha = memberRepository.findByPublicId(publicId).orElseThrow();

        assertThat(memberRepository.findAll()).hasSize(2);
        assertThat(mapping(entrance, asha).getDeviceUserId()).isEqualTo("2");
        assertThat(mapping(exit, asha).getDeviceUserId()).isEqualTo("1");
        assertThat(memberDeviceMappingRepository.findByMemberId(asha.getId())).hasSize(2);
        assertThat(deviceSyncCommandRepository.findAll()).isEmpty();

        JsonNode onEntrance = latest(entranceId);
        JsonNode onExit = latest(exitId);
        assertThat(onEntrance.get("deviceUserId").asString()).isEqualTo("2");
        assertThat(onExit.get("deviceUserId").asString()).isEqualTo("1");
        assertThat(onEntrance.get("revision").asLong()).isEqualTo(2);
        assertThat(onExit.get("revision").asLong()).isEqualTo(1);
        for (JsonNode item : List.of(onEntrance, onExit)) {
            assertThat(item.get("name").asString()).isEqualTo("Asha Shah");
            assertThat(item.get("present").asBoolean()).isTrue();
            assertThat(item.get("facePresent").asBoolean()).isTrue();
            byte[] face = Base64.getDecoder().decode(item.get("faceBase64").asString());
            assertThat(com.example.gym.face.FaceStorageService.sha256(face))
                    .isEqualToIgnoringCase(item.get("faceSha256").asString());
            assertThat(item.toString()).doesNotContain(publicId);
        }
        assertThat(onEntrance.get("faceSha256").asString()).isEqualTo(onExit.get("faceSha256").asString());

        ack(entranceId, onEntrance).andExpect(status().isOk());
        ack(exitId, onExit).andExpect(status().isOk());
        assertConverged(entrance, 2);
        assertConverged(exit, 1);
        assertThat(auditLogRepository.findAll())
                .filteredOn(row -> AuditActions.MEMBER_CREATED.equals(row.getAction())
                        && publicId.equals(row.getResourceId()))
                .hasSize(1);
    }

    @Test
    void anOccupiedIdOnOneReaderRetriesThereOnly() throws Exception {
        String publicId = create("Asha", "V18-OCC", "1803");
        Member asha = memberRepository.findByPublicId(publicId).orElseThrow();
        DesiredMemberProjection exitBefore = projection(exit, asha);

        mockMvc.perform(post("/internal/gateway/desired/occupied")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + entranceId + "\",\"revision\":1,\"deviceUserId\":\"1\"}"))
                .andExpect(status().isOk());

        assertThat(mapping(entrance, asha).getDeviceUserId()).isEqualTo("2");
        assertThat(projection(entrance, asha).getRevision()).isEqualTo(2);
        assertThat(readerBlockedUserRepository.findByDeviceId(entrance))
                .extracting(row -> row.getDeviceUserId()).containsExactly("1");

        assertThat(mapping(exit, asha).getDeviceUserId()).isEqualTo("1");
        DesiredMemberProjection exitAfter = projection(exit, asha);
        assertThat(exitAfter.getRevision()).isEqualTo(exitBefore.getRevision()).isEqualTo(1);
        assertThat(exitAfter.getDeviceUserId()).isEqualTo("1");
        assertThat(readerBlockedUserRepository.findByDeviceId(exit)).isEmpty();
        assertThat(cursor(exit).getDesiredRevision()).isEqualTo(1);

        ack(entranceId, latest(entranceId)).andExpect(status().isOk());
        ack(exitId, latest(exitId)).andExpect(status().isOk());
        assertConverged(entrance, 2);
        assertConverged(exit, 1);
    }

    @Test
    void anOfflineReaderConvergesWhenItReturns() throws Exception {
        String publicId = create("Asha", "V18-OFF", "1804");
        Member asha = memberRepository.findByPublicId(publicId).orElseThrow();
        ack(entranceId, latest(entranceId)).andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/members/" + publicId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\"}"))
                .andExpect(status().isOk());
        ack(entranceId, latest(entranceId)).andExpect(status().isOk());
        assertConverged(entrance, 2);
        assertThat(cursor(exit).getAppliedRevision()).isZero();
        assertThat(cursor(exit).getDesiredRevision()).isEqualTo(2);

        JsonNode page = pull(exitId, 0);
        assertThat(page.get("items")).hasSize(1);
        JsonNode returned = page.get("items").get(0);
        assertThat(returned.get("revision").asLong()).isEqualTo(2);
        assertThat(returned.get("name").asString()).isEqualTo("Asha Rao");
        assertThat(returned.get("deviceUserId").asString()).isEqualTo(mapping(exit, asha).getDeviceUserId());
        ack(exitId, returned).andExpect(status().isOk());

        assertConverged(exit, 2);
        assertThat(projection(exit, asha).getReaderName()).isEqualTo("Asha Rao");
    }

    @Test
    void oneReaderFailingDoesNotUndoTheMemberOrTheOtherReader() throws Exception {
        String publicId = create("Asha", "V18-FAIL", "1805");
        Member asha = memberRepository.findByPublicId(publicId).orElseThrow();
        JsonNode onExit = latest(exitId);

        ObjectNode wrongFace = (ObjectNode) jsonMapper.readTree(ackBody(exitId, onExit));
        wrongFace.put("faceSha256", "0".repeat(64));
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongFace.toString()))
                .andExpect(status().isConflict());
        ack(entranceId, latest(entranceId)).andExpect(status().isOk());

        assertThat(memberRepository.findByPublicId(publicId)).isPresent();
        assertThat(memberFaceRepository.findByMemberId(asha.getId())).isPresent();
        assertConverged(entrance, 1);
        assertThat(cursor(exit).getAppliedRevision()).isZero();
        assertThat(projection(exit, asha).isPresentOnReader()).isTrue();
        assertThat(mapping(exit, asha).getDeviceUserId()).isEqualTo("1");

        JsonNode sync = deviceSync(publicId);
        assertThat(row(sync, entranceId).get("applied").asBoolean()).isTrue();
        assertThat(row(sync, exitId).get("applied").asBoolean()).isFalse();
        assertThat(row(sync, exitId).get("revision").asLong()).isEqualTo(1);
        assertThat(row(sync, exitId).get("appliedRevision").asLong()).isZero();

        ack(exitId, latest(exitId)).andExpect(status().isOk());
        assertConverged(exit, 1);
        assertThat(row(deviceSync(publicId), exitId).get("applied").asBoolean()).isTrue();
    }

    @Test
    void aGymWithNoReaderSavesTheMemberAndPublishesNothing() throws Exception {
        Tenant empty = createTenant("Empty Gym", "empty-gym");
        createUser(empty.getId(), "empty-admin", "empty-admin@gym.local", "GYM_ADMIN");
        String emptyToken = tokenFor("empty-admin");
        String loose = readJson(mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + emptyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Loose\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.40\",\"port\":37777}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        Long looseId = deviceRepository.findByPublicId(loose).orElseThrow().getId();
        long projectionsBefore = desiredMemberProjectionRepository.count();

        String publicId = createMemberId(emptyToken, "{\"firstName\":\"Solo\",\"lastName\":\"Shah\"}");

        Member solo = memberRepository.findByPublicId(publicId).orElseThrow();
        assertThat(memberFaceRepository.findByMemberId(solo.getId())).isPresent();
        assertThat(memberDeviceMappingRepository.findByMemberId(solo.getId())).isEmpty();
        assertThat(desiredMemberProjectionRepository.count()).isEqualTo(projectionsBefore);
        assertThat(cursor(looseId).getDesiredRevision()).isZero();
        assertThat(deviceSyncCommandRepository.findAll()).isEmpty();
        assertThat(deviceSync(publicId, emptyToken).get("devices")).isEmpty();
    }

    @Test
    void aMemberIsNotCreatedWithoutAFaceOrWithAReaderId() throws Exception {
        long members = memberRepository.count();
        mockMvc.perform(multipart("/api/v1/members")
                        .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                                "{\"firstName\":\"Faceless\"}".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/v1/members")
                        .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                                "{\"firstName\":\"Empty\"}".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("face", "face.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        int jsonCreate = postJson("/api/v1/members", "{\"firstName\":\"Json\"}")
                .andReturn().getResponse().getStatus();
        assertThat(HttpStatus.valueOf(jsonCreate).is2xxSuccessful()).isFalse();

        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(memberDeviceMappingRepository.findAll()).isEmpty();
        assertThat(desiredMemberProjectionRepository.findAll()).isEmpty();
        assertThat(cursor(entrance).getDesiredRevision()).isZero();
        assertThat(cursor(exit).getDesiredRevision()).isZero();
    }

    @Test
    void theMemberPageShowsEachReadersAppliedOrPendingState() throws Exception {
        String publicId = create("Asha", "V18-PAGE", "1806");

        JsonNode before = deviceSync(publicId);
        assertThat(before.get("devices")).hasSize(2);
        for (String reader : List.of(entranceId, exitId)) {
            JsonNode row = row(before, reader);
            assertThat(row.get("applied").asBoolean()).isFalse();
            assertThat(row.get("present").asBoolean()).isTrue();
            assertThat(row.get("facePresent").asBoolean()).isTrue();
            assertThat(row.get("revision").asLong()).isEqualTo(1);
            assertThat(row.get("appliedRevision").asLong()).isZero();
            assertThat(row.get("deviceUserId").asString()).isEqualTo("1");
            assertThat(row.has("userSyncState")).isFalse();
            assertThat(row.has("openCommands")).isFalse();
        }

        ack(entranceId, latest(entranceId)).andExpect(status().isOk());

        JsonNode after = deviceSync(publicId);
        assertThat(row(after, entranceId).get("applied").asBoolean()).isTrue();
        assertThat(row(after, entranceId).get("appliedRevision").asLong()).isEqualTo(1);
        assertThat(row(after, exitId).get("applied").asBoolean()).isFalse();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String create(String firstName, String code, String serial) throws Exception {
        return createMemberId(token, """
                {"firstName":"%s","lastName":"Shah","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, code, serial));
    }

    /** Ria is on the entrance only, as when the exit was added after she was created. */
    private void onlyOnEntrance(String firstName, String code, String serial) throws Exception {
        Device device = deviceRepository.findById(entrance).orElseThrow();
        Member member = saveMember(tenant.getId(), code, firstName, "Shah", serial);
        desiredProjectionService.write(member, device, memberFaceService.storeUploaded(member, faceJpeg()));
        ack(entranceId, latest(entranceId)).andExpect(status().isOk());
    }

    private void assertConverged(Long deviceId, long revision) {
        ReaderRevision cursor = cursor(deviceId);
        assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(revision);
    }

    private ReaderRevision cursor(Long deviceId) {
        return readerRevisionRepository.findByDeviceId(deviceId).orElseThrow();
    }

    private MemberDeviceMapping mapping(Long deviceId, Member member) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private DesiredMemberProjection projection(Long deviceId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(deviceId, member.getId()).orElseThrow();
    }

    private JsonNode deviceSync(String memberPublicId) throws Exception {
        return deviceSync(memberPublicId, token);
    }

    private JsonNode deviceSync(String memberPublicId, String bearer) throws Exception {
        return readJson(mockMvc.perform(get("/api/v1/members/" + memberPublicId + "/device-sync")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode row(JsonNode sync, String devicePublicId) {
        for (JsonNode row : sync.get("devices")) {
            if (devicePublicId.equals(row.get("deviceId").asString())) {
                return row;
            }
        }
        throw new AssertionError("No row for reader " + devicePublicId);
    }

    /** The highest revision this reader can pull. */
    private JsonNode latest(String devicePublicId) throws Exception {
        JsonNode page = pull(devicePublicId, 0);
        JsonNode items = page.get("items");
        assertThat(items).isNotEmpty();
        return items.get(items.size() - 1);
    }

    private JsonNode pull(String devicePublicId, long after) throws Exception {
        return readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", devicePublicId)
                        .param("after", Long.toString(after)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions ack(String devicePublicId, JsonNode item) throws Exception {
        return mockMvc.perform(post("/internal/gateway/desired/ack")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ackBody(devicePublicId, item)));
    }

    private static String ackBody(String devicePublicId, JsonNode item) {
        String nameEx = item.get("nameEx") == null || item.get("nameEx").isNull()
                ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        return """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(devicePublicId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                item.get("name").asString(), nameEx, item.get("userStatus").asInt(),
                item.get("validFrom").asString(), item.get("validTo").asString(),
                item.get("faceSha256").asString());
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
