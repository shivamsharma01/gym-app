package com.example.gym.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.Device;
import com.example.gym.face.MemberFaceService;
import com.example.gym.member.Member;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * One gym with one enrolled gateway, and what that gateway says on behalf of its readers:
 * single observations, trusted lists, punches, and read-back acknowledgements.
 */
public abstract class AbstractReaderIT extends AbstractIntegrationTest {

    @Autowired
    protected DesiredProjectionService desiredProjectionService;

    @Autowired
    protected MemberFaceService memberFaceService;

    protected Tenant tenant;
    protected String token;
    protected String gatewayPublicId;
    protected String gatewayToken;

    protected void gymWithGateway(String slug) throws Exception {
        resetDatabase();
        tenant = createTenant(slug + " Gym", slug);
        createUser(tenant.getId(), slug + "-admin", slug + "-admin@gym.local", "GYM_ADMIN");
        token = tokenFor(slug + "-admin");
        JsonNode gateway = readJson(postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        gatewayPublicId = gateway.get("id").asString();
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayPublicId + "\",\"enrollmentToken\":\""
                                + gateway.get("token").asString() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        gatewayToken = readJson(enrolled).get("credential").asString();
    }

    protected String createReader(String name) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"ENTRANCE\",\"host\":\"10.0.0.20\",\"port\":37777,"
                        + "\"gatewayId\":\"" + gatewayPublicId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    protected Long readerPk(String readerPublicId) {
        return deviceRepository.findByPublicId(readerPublicId).orElseThrow().getId();
    }

    /** A member placed on one reader only, as when the other readers were added later. */
    protected Member onlyOn(String readerPublicId, String firstName, String lastName, String code, String serial)
            throws Exception {
        Device reader = deviceRepository.findByPublicId(readerPublicId).orElseThrow();
        Member member = saveMember(reader.getTenantId(), code, firstName, lastName, serial);
        desiredProjectionService.write(member, reader, memberFaceService.storeUploaded(member, faceJpeg()));
        return member;
    }

    /** A paid membership running from five days ago for thirty days. Returns its public id. */
    protected String paidMembership(Member member) throws Exception {
        LocalDate today = LocalDate.now();
        String planId = readJson(postJson("/api/v1/plans", "{\"name\":\"Monthly " + member.getMemberCode()
                + "\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        String membership = readJson(postJson("/api/v1/memberships", "{\"memberId\":\"" + member.getPublicId()
                + "\",\"planId\":\"" + planId + "\",\"startDate\":\"" + today.minusDays(5)
                + "\",\"endDate\":\"" + today.plusDays(25) + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        postJson("/api/v1/payments", "{\"memberId\":\"" + member.getPublicId() + "\",\"membershipId\":\"" + membership
                + "\",\"amount\":1000.00,\"method\":\"CASH\"}").andExpect(status().isCreated());
        return membership;
    }

    protected DesiredMemberProjection projection(String readerPublicId, Member member) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(readerPk(readerPublicId), member.getId())
                .orElseThrow();
    }

    /** One reader user as the gateway uploads it. A null face means the gateway reported none. */
    protected static String person(String deviceUserId, String name, int userStatus, String validFrom,
                                   String validTo, String faceSha256) {
        return """
                {"deviceUserId":"%s","name":"%s","nameEx":null,"userStatus":%d,"validFrom":"%s","validTo":"%s",\
                "authority":"Customer","doorNum":1,"timeSectionNum":1,"faceSha256":%s,"isNew":true,"deleted":false}
                """.formatted(deviceUserId, name, userStatus, validFrom, validTo,
                faceSha256 == null ? "null" : "\"" + faceSha256 + "\"").trim();
    }

    /** The reader exactly as the desired row says, with a different status or face where given. */
    protected static String asDesired(DesiredMemberProjection row, int userStatus, String faceSha256) {
        return person(row.getDeviceUserId(), row.getReaderName(), userStatus, row.getValidFrom(), row.getValidTo(),
                faceSha256);
    }

    protected void readerSays(String readerPublicId, String payload) throws Exception {
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, readerPublicId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    /** A complete list: its length matches the announced total. */
    protected void trustedRoster(String readerPublicId, String... people) throws Exception {
        readerSays(readerPublicId, "{\"announcedTotal\":" + people.length + ",\"users\":["
                + String.join(",", people) + "]}");
    }

    protected void punch(String readerPublicId, String deviceUserId, Instant at, int recNo) throws Exception {
        String body = "{\"deviceId\":\"" + readerPublicId + "\",\"punches\":[{\"deviceUserId\":\"" + deviceUserId
                + "\",\"occurredAt\":\"" + at + "\",\"method\":\"FACE\",\"granted\":true,\"recNo\":" + recNo + "}]}";
        mockMvc.perform(post("/internal/gateway/attendance")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    protected JsonNode pull(String readerPublicId, long after) throws Exception {
        return readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", readerPublicId)
                        .param("after", Long.toString(after)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The gateway applies every pending revision and the read-back matches each one. */
    protected void ackEverything(String readerPublicId) throws Exception {
        long applied = readerRevisionRepository.findByDeviceId(readerPk(readerPublicId))
                .map(cursor -> cursor.getAppliedRevision()).orElse(0L);
        for (JsonNode item : pull(readerPublicId, applied).get("items")) {
            mockMvc.perform(post("/internal/gateway/desired/ack")
                            .header("Authorization", "Bearer " + gatewayToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(readBack(readerPublicId, item)))
                    .andExpect(status().isOk());
        }
    }

    private static String readBack(String readerPublicId, JsonNode item) {
        long revision = item.get("revision").asLong();
        String deviceUserId = item.get("deviceUserId").asString();
        if (!item.get("present").asBoolean()) {
            return """
                    {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"","userStatus":0,\
                    "validFrom":"","validTo":"","faceSha256":"","present":false,"failCode":"NO_RECORD"}
                    """.formatted(readerPublicId, revision, deviceUserId);
        }
        String nameEx = item.get("nameEx").isNull() ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        boolean face = item.get("facePresent").asBoolean();
        return """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,"userStatus":%d,\
                "validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true%s}
                """.formatted(readerPublicId, revision, deviceUserId, item.get("name").asString(), nameEx,
                item.get("userStatus").asInt(), item.get("validFrom").asString(), item.get("validTo").asString(),
                face ? item.get("faceSha256").asString() : "", face ? "" : ",\"failCode\":\"UNKNOWN\"");
    }

    /** A photo the gateway uploaded from a reader. Each colour is a different photo. */
    protected String uploadFace(Color color) throws Exception {
        String body = mockMvc.perform(post("/internal/gateway/faces")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(jpeg(color)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(body).get("sha256").asString();
    }

    protected ResultActions postJson(String path, String body) throws Exception {
        return postAs(token, path, body);
    }

    protected ResultActions postAs(String bearer, String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body));
    }

    protected ResultActions putJson(String path, String body) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected JsonNode getJson(String path) throws Exception {
        return readJson(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static byte[] jpeg(Color color) throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
