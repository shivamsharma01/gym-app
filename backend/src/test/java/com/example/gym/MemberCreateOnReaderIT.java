package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * V1: a member with a face is created on one flagged reader from desired state. The old outbox does
 * not also create that user. Unflagged readers keep the outbox.
 */
class MemberCreateOnReaderIT extends AbstractIntegrationTest {

    private String token;
    private String gatewayToken;
    private String otherGatewayToken;
    private String flaggedId;
    private String otherId;
    private Long flagged;
    private Long other;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("Projection Gym", "projection-gym");
        createUser(tenant.getId(), "projection-admin", "projection-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("projection-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String gatewayId = readJson(createdGateway).get("id").asString();
        gatewayToken = enroll(gatewayId, readJson(createdGateway).get("token").asString());

        String otherGateway = postJson("/api/v1/gateways", "{\"name\":\"Other\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        otherGatewayToken = enroll(readJson(otherGateway).get("id").asString(),
                readJson(otherGateway).get("token").asString());

        flaggedId = createDevice("Entrance", true, gatewayId);
        otherId = createDevice("Exit", false, gatewayId);
        flagged = deviceRepository.findByPublicId(flaggedId).orElseThrow().getId();
        other = deviceRepository.findByPublicId(otherId).orElseThrow().getId();
    }

    @Test
    void createAppliesTheAllocatedUserAndDoesNotUseTheOutbox() throws Exception {
        String fullName = "A".repeat(40) + " Shah";
        JsonNode created = createOnReader("A".repeat(40), "Shah", "M-ONE", "5001", flaggedId);
        String publicId = created.get("id").asString();
        assertThat(publicId).isNotEqualTo("1");

        MemberDeviceMapping mapping = memberDeviceMappingRepository
                .findByDeviceIdAndMemberId(flagged, memberRepository.findByPublicId(publicId).orElseThrow().getId())
                .orElseThrow();
        assertThat(mapping.getDeviceUserId()).isEqualTo("1");
        assertThat(mapping.getDeviceUserId()).isNotEqualTo(publicId).isNotEqualTo("5001");

        assertThat(commands(flagged)).isEmpty();
        assertThat(commands(other)).extracting(DeviceSyncCommand::getType).contains(SyncCommandType.CREATE_USER);

        JsonNode page = readJson(pull(gatewayToken, flaggedId, 0).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(page.get("desiredRevision").asLong()).isEqualTo(1);
        assertThat(page.get("appliedRevision").asLong()).isZero();
        assertThat(page.get("items")).hasSize(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("deviceUserId").asString()).isEqualTo("1");
        assertThat(item.get("name").asString()).isEqualTo(fullName.substring(0, 31));
        assertThat(item.get("nameEx").asString()).isEqualTo(fullName);
        assertThat(item.get("userStatus").asInt()).isZero();
        assertThat(item.get("authority").asString()).isEqualTo("Customer");
        assertThat(item.get("doorNum").asInt()).isEqualTo(1);
        assertThat(item.get("timeSectionNum").asInt()).isEqualTo(1);
        assertThat(item.get("validFrom").asString()).endsWith("T00:00:00+05:30");
        assertThat(item.get("validTo").asString()).endsWith("T23:59:59+05:30");
        assertThat(item.has("memberId")).isFalse();
        assertThat(item.toString()).doesNotContain(publicId);
        byte[] face = Base64.getDecoder().decode(item.get("faceBase64").asString());
        assertThat(sha(face)).isEqualToIgnoringCase(item.get("faceSha256").asString());

        mockMvc.perform(get("/api/v1/members/" + publicId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        ack(item, flaggedId).andExpect(status().isOk());
        JsonNode applied = readJson(pull(gatewayToken, flaggedId, 0).andReturn().getResponse().getContentAsString());
        assertThat(applied.get("appliedRevision").asLong()).isEqualTo(applied.get("desiredRevision").asLong()).isEqualTo(1);
        JsonNode afterApplied = readJson(pull(gatewayToken, flaggedId, applied.get("appliedRevision").asLong())
                .andReturn().getResponse().getContentAsString());
        assertThat(afterApplied.get("items")).isEmpty();
    }

    @Test
    void occupiedIdIsReplacedByTheNextIntegerAndTheOldRevisionIsNotApplied() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "M-OCC", "5002", flaggedId);
        String publicId = created.get("id").asString();
        JsonNode first = pullItem(0);
        assertThat(first.get("deviceUserId").asString()).isEqualTo("1");

        mockMvc.perform(post("/internal/gateway/desired/occupied")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + flaggedId + "\",\"revision\":1,\"deviceUserId\":\"1\"}"))
                .andExpect(status().isOk());

        MemberDeviceMapping mapping = mapping(publicId, flagged);
        assertThat(mapping.getDeviceUserId()).isEqualTo("2");
        ack(first, flaggedId).andExpect(status().isConflict());

        JsonNode retry = pullItem(0);
        assertThat(retry.get("revision").asLong()).isEqualTo(2);
        assertThat(retry.get("deviceUserId").asString()).isEqualTo("2");
        ack(retry, flaggedId).andExpect(status().isOk());

        JsonNode second = createOnReader("Bea", "Shah", "M-NEXT", "5003", flaggedId);
        MemberDeviceMapping next = mapping(second.get("id").asString(), flagged);
        assertThat(next.getDeviceUserId()).isEqualTo("3");
        ack(pullItem(2), flaggedId).andExpect(status().isOk());

        JsonNode done = readJson(pull(gatewayToken, flaggedId, 0).andReturn().getResponse().getContentAsString());
        assertThat(done.get("appliedRevision").asLong()).isEqualTo(done.get("desiredRevision").asLong());
    }

    @Test
    void ackIsRejectedWhenTheFaceHashDoesNotMatch() throws Exception {
        createOnReader("Asha", "Shah", "M-HASH", "5004", flaggedId);
        JsonNode item = pullItem(0);
        ObjectNode body = (ObjectNode) jsonMapper.readTree(ackBody(item, flaggedId));
        body.put("faceSha256", "0".repeat(64));
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isConflict());
        body.remove("faceSha256");
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());

        JsonNode page = readJson(pull(gatewayToken, flaggedId, 0).andReturn().getResponse().getContentAsString());
        assertThat(page.get("appliedRevision").asLong()).isZero();
        assertThat(page.get("desiredRevision").asLong()).isEqualTo(1);
        assertThat(page.get("items")).hasSize(1);
    }

    @Test
    void desiredPullRequiresTheReadersGateway() throws Exception {
        pull(null, flaggedId, 0).andExpect(status().isUnauthorized());
        pull(otherGatewayToken, flaggedId, 0).andExpect(status().isForbidden());
        pull(gatewayToken, otherId, 0).andExpect(status().isNotFound());
    }

    private JsonNode pullItem(long after) throws Exception {
        JsonNode page = readJson(pull(gatewayToken, flaggedId, after).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(page.get("items")).isNotEmpty();
        return page.get("items").get(0);
    }

    private org.springframework.test.web.servlet.ResultActions pull(String bearer, String deviceId, long after)
            throws Exception {
        var request = get("/internal/gateway/desired")
                .param("deviceId", deviceId)
                .param("after", Long.toString(after));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions ack(JsonNode item, String deviceId) throws Exception {
        return mockMvc.perform(post("/internal/gateway/desired/ack")
                .header("Authorization", "Bearer " + gatewayToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ackBody(item, deviceId)));
    }

    private static String ackBody(JsonNode item, String deviceId) {
        String nameEx = item.get("nameEx") == null || item.get("nameEx").isNull()
                ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        return """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,\
                "userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s"}
                """.formatted(deviceId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                item.get("name").asString(), nameEx, item.get("userStatus").asInt(),
                item.get("validFrom").asString(), item.get("validTo").asString(),
                item.get("faceSha256").asString());
    }

    private JsonNode createOnReader(String firstName, String lastName, String code, String serial, String readerId)
            throws Exception {
        String member = """
                {"firstName":"%s","lastName":"%s","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, lastName, code, serial);
        MvcResult result = mockMvc.perform(multipart("/api/v1/members")
                        .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                                member.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("face", "face.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg()))
                        .param("readerId", readerId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(result.getResponse().getContentAsString());
    }

    private String createDevice(String name, boolean projection, String gatewayId) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayId + "\",\"projectionEnabled\":" + projection + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String enroll(String gatewayId, String enrollmentToken) throws Exception {
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + enrollmentToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(enrolled).get("credential").asString();
    }

    private java.util.List<DeviceSyncCommand> commands(Long deviceId) {
        return deviceSyncCommandRepository.findAll().stream()
                .filter(command -> deviceId.equals(command.getDeviceId()))
                .toList();
    }

    private MemberDeviceMapping mapping(String memberPublicId, Long deviceId) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(
                deviceId, memberRepository.findByPublicId(memberPublicId).orElseThrow().getId()).orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String sha(byte[] bytes) {
        return com.example.gym.face.FaceStorageService.sha256(bytes);
    }

    private static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
