package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.DesiredProjectionService;
import com.example.gym.device.GatewayConnectedEvent;
import com.example.gym.device.GatewayConnectedListener;
import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceReviewItem;
import com.example.gym.device.domain.DeviceReviewSnapshot;
import com.example.gym.device.domain.PendingEnrollment;
import com.example.gym.face.MemberFace;
import com.example.gym.member.DeviceAuthority;
import com.example.gym.member.Member;
import com.example.gym.member.MemberStatus;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * V1 desired-revision path against the real allocator, the websocket notice, and FakeReader.
 * The harness is the gateway receive loop for one flagged reader. It does not choose device ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class V1DesiredRevisionIT extends AbstractIntegrationTest {

    private static Path harnessDll;

    @LocalServerPort
    private int port;

    @Autowired
    private GatewayConnectedListener connected;

    @Autowired
    private DesiredProjectionService desiredProjection;

    private String token;
    private String gatewayToken;
    private String gatewayPublicId;
    private String flaggedId;
    private Long flagged;
    private Long other;

    @BeforeAll
    static void buildHarness() throws Exception {
        Path repo = repoRoot();
        Path project = repo.resolve("gateway/tests/Gym.Gateway.V1Harness/Gym.Gateway.V1Harness.csproj");
        harnessDll = repo.resolve("gateway/tests/Gym.Gateway.V1Harness/bin/Release/net10.0/Gym.Gateway.V1Harness.dll");
        ProcessBuilder build = new ProcessBuilder(dotnet(), "build", project.toString(), "-c", "Release", "-v", "q");
        build.environment().put("DOTNET_ROOT", dotnetHome());
        build.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        build.environment().put("DOTNET_NOLOGO", "1");
        build.environment().put("PATH", dotnetHome() + ":" + build.environment().getOrDefault("PATH", ""));
        build.redirectErrorStream(true);
        Process process = build.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> output.append(readQuietly(process.getInputStream())), "v1-harness-build");
        reader.start();
        boolean finished = process.waitFor(4, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        reader.join(5_000);
        if (!finished || process.exitValue() != 0 || !Files.exists(harnessDll)) {
            throw new IllegalStateException("Harness build failed\n" + output);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        Tenant tenant = createTenant("V1 Gym", "v1-gym");
        createUser(tenant.getId(), "v1-admin", "v1-admin@gym.local", "GYM_ADMIN");
        token = tokenFor("v1-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayPublicId = readJson(createdGateway).get("id").asString();
        gatewayToken = enroll(gatewayPublicId, readJson(createdGateway).get("token").asString());

        flaggedId = createDevice("Entrance", true, gatewayPublicId);
        flagged = deviceRepository.findByPublicId(flaggedId).orElseThrow().getId();
        other = deviceRepository.findByPublicId(createDevice("Exit", false, gatewayPublicId)).orElseThrow().getId();
    }

    @Test
    void memberCreateReachesTheReaderAndAcksOnlyAfterReadBack() throws Exception {
        try (Harness harness = start("happy", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V1-HAPPY", "7101");
            String publicId = created.get("id").asString();
            JsonNode report = harness.finish();

            assertThat(report.get("acked").asBoolean()).isTrue();
            assertThat(report.get("ackCount").asInt()).isEqualTo(1);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(1);
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("createdId").asString()).isEqualTo("1");
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1");
            assertNotPublicId(report, publicId);
            JsonNode user = user(report, "1");
            assertThat(user.get("name").asString()).isEqualTo("Asha Shah");
            assertThat(user.get("nameEx").isNull()).isTrue();

            MemberDeviceMapping mapping = mapping(publicId);
            assertThat(mapping.getDeviceUserId()).isEqualTo("1").isNotEqualTo(publicId).isNotEqualTo("7101");
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(1);
            assertThat(commands(flagged)).isEmpty();
            assertThat(commands(other)).extracting(DeviceSyncCommand::getType).contains(SyncCommandType.CREATE_USER);
            mockMvc.perform(get("/api/v1/members/" + publicId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void occupiedIdIsReplacedByTheNextServerInteger() throws Exception {
        try (Harness harness = start("occupied", "1")) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V1-OCC", "7102");
            String publicId = created.get("id").asString();
            JsonNode report = harness.finish();

            assertThat(report.get("occupiedId").asString()).isEqualTo("1");
            assertThat(report.get("createdId").asString()).isEqualTo("2");
            assertThat(report.get("ackCount").asInt()).isEqualTo(1);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(2);
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("acked").asBoolean()).isTrue();
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 2", "InsertFace 2");
            assertThat(Long.parseLong(report.get("createdId").asString()))
                    .isEqualTo(Long.parseLong(report.get("occupiedId").asString()) + 1);
            JsonNode untouched = user(report, "1");
            assertThat(untouched.get("name").asString()).isEqualTo("Already there");
            assertThat(untouched.get("faceHex").asString()).isEqualTo("09090909");
            assertThat(user(report, "2").get("id").asString()).isEqualTo("2");
            assertNotPublicId(report, publicId);

            assertThat(mapping(publicId).getDeviceUserId()).isEqualTo("2").isNotEqualTo(publicId);
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(2);
            assertThat(readerBlockedUserRepository.existsByDeviceIdAndDeviceUserId(flagged, "1")).isTrue();
            assertThat(commands(flagged)).isEmpty();
        }
    }

    @Test
    void faceReadBackMismatchDoesNotAck() throws Exception {
        try (Harness harness = start("face", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V1-FACE", "7103");
            String publicId = created.get("id").asString();
            JsonNode report = harness.finish();

            assertThat(report.get("acked").asBoolean()).isFalse();
            assertThat(report.get("ackCount").asInt()).isZero();
            assertThat(report.get("appliedLocal").asLong()).isZero();
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1");
            assertNotPublicId(report, publicId);

            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isZero();
            assertThat(cursor.getDesiredRevision()).isEqualTo(1);
            assertThat(commands(flagged)).isEmpty();
        }
    }

    @Test
    void restartDoesNotExecuteTheRevisionAgain() throws Exception {
        try (Harness harness = start("restart", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V1-RESTART", "7104");
            String publicId = created.get("id").asString();
            JsonNode report = harness.finish();

            assertThat(report.get("acked").asBoolean()).isTrue();
            assertThat(report.get("ackCount").asInt()).isEqualTo(1);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(1);
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("executedAgain").asBoolean()).isFalse();
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1");
            assertNotPublicId(report, publicId);
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(1);
            assertThat(commands(flagged)).isEmpty();
        }
    }

    @Test
    void disallowThenAllowKeepsTheUserAndTheFace() throws Exception {
        try (Harness harness = start("freeze", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V2-FREEZE", "7201");
            String publicId = created.get("id").asString();
            awaitApplied(1);
            mockMvc.perform(delete("/api/v1/members/" + publicId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
            awaitApplied(2);
            mockMvc.perform(post("/api/v1/members/" + publicId + "/reactivate")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            awaitApplied(3);
            JsonNode report = harness.finish();

            assertThat(report.get("ackCount").asInt()).isEqualTo(3);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(3);
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1", "ReplaceUser 1", "ReplaceUser 1");
            JsonNode user = user(report, "1");
            assertThat(user.get("status").asInt()).isZero();
            assertThat(user.get("faceSha256").asString()).isNotBlank();
            assertThat(user.get("id").asString()).isEqualTo("1");
            assertNotPublicId(report, publicId);

            assertThat(mapping(publicId).getDeviceUserId()).isEqualTo("1");
            assertThat(memberRepository.findByPublicId(publicId).orElseThrow().getStatus())
                    .isEqualTo(MemberStatus.ACTIVE);
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(3);
            DesiredMemberProjection projection = desiredMemberProjectionRepository
                    .findByDeviceIdAndMemberId(flagged, memberRepository.findByPublicId(publicId).orElseThrow().getId())
                    .orElseThrow();
            assertThat(projection.getUserStatus()).isZero();
            assertThat(projection.getDeviceUserId()).isEqualTo("1");
            assertThat(commands(flagged)).isEmpty();
        }
    }

    @Test
    void statusReadBackStillEnabledDoesNotAck() throws Exception {
        try (Harness harness = start("held", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V2-HELD", "7202");
            String publicId = created.get("id").asString();
            Long memberId = memberRepository.findByPublicId(publicId).orElseThrow().getId();
            awaitApplied(1);
            mockMvc.perform(delete("/api/v1/members/" + publicId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
            JsonNode report = harness.finish();

            assertThat(report.get("ackCount").asInt()).isEqualTo(1);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(1);
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1", "ReplaceUser 1");
            JsonNode user = user(report, "1");
            assertThat(user.get("id").asString()).isEqualTo("1");
            assertThat(user.get("faceSha256").asString()).isNotBlank();
            assertNotPublicId(report, publicId);

            assertThat(mapping(publicId).getDeviceUserId()).isEqualTo("1");
            assertThat(memberRepository.findByPublicId(publicId).orElseThrow().getStatus())
                    .isEqualTo(MemberStatus.INACTIVE);
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(1);
            assertThat(cursor.getDesiredRevision()).isEqualTo(2);
            DesiredMemberProjection projection = desiredMemberProjectionRepository
                    .findByDeviceIdAndMemberId(flagged, memberId)
                    .orElseThrow();
            assertThat(projection.getUserStatus()).isEqualTo(1);
            assertThat(projection.getDeviceUserId()).isEqualTo("1");
            assertThat(commands(flagged)).isEmpty();
        }
    }

    @Test
    void nameAndDatesReplaceTheUserOnTheFlaggedReader() throws Exception {
        try (Harness harness = start("edit", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V3-NAME", "7301");
            String publicId = created.get("id").asString();
            awaitApplied(1);

            String fullName = "Priya Nandini Kapoor the reader name";
            mockMvc.perform(put("/api/v1/members/" + publicId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"firstName":"Priya Nandini Kapoor","lastName":"the reader name"}
                                    """))
                    .andExpect(status().isOk());
            awaitApplied(2);

            LocalDate start = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(2);
            LocalDate end = start.plusDays(40);
            String planId = readJson(postJson("/api/v1/plans",
                    "{\"name\":\"Monthly\",\"price\":1000.00,\"currency\":\"INR\",\"durationDays\":30}")
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString()).get("id").asString();
            String membershipId = readJson(postJson("/api/v1/memberships",
                    "{\"memberId\":\"" + publicId + "\",\"planId\":\"" + planId
                            + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}")
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString()).get("id").asString();
            awaitApplied(3);

            LocalDate later = end.plusDays(15);
            mockMvc.perform(put("/api/v1/memberships/" + membershipId + "/dates")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startDate\":\"" + start + "\",\"endDate\":\"" + later + "\"}"))
                    .andExpect(status().isOk());
            awaitApplied(4);
            JsonNode report = harness.finish();

            assertThat(report.get("ackCount").asInt()).isEqualTo(4);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(4);
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1", "ReplaceUser 1", "ReplaceUser 1", "ReplaceUser 1");
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .noneMatch(line -> line.contains("SynchronizeTime"));
            JsonNode user = user(report, "1");
            assertThat(user.get("id").asString()).isEqualTo("1");
            assertThat(user.get("name").asString()).isEqualTo(fullName.substring(0, 31));
            assertThat(user.get("nameEx").asString()).isEqualTo(fullName);
            assertThat(user.get("validFrom").asString()).isEqualTo(readerTime(start, LocalTime.MIN));
            assertThat(user.get("validTo").asString()).isEqualTo(readerTime(later, LocalTime.of(23, 59, 59)));
            assertThat(user.get("faceSha256").asString()).isNotBlank();
            assertNotPublicId(report, publicId);

            assertThat(mapping(publicId).getDeviceUserId()).isEqualTo("1");
            assertThat(memberRepository.findByPublicId(publicId).orElseThrow().getPublicId()).isEqualTo(publicId);
            DesiredMemberProjection projection = desiredMemberProjectionRepository
                    .findByDeviceIdAndMemberId(flagged, memberRepository.findByPublicId(publicId).orElseThrow().getId())
                    .orElseThrow();
            assertThat(projection.getReaderName()).isEqualTo(fullName.substring(0, 31));
            assertThat(projection.getReaderNameEx()).isEqualTo(fullName);
            assertThat(projection.getValidFrom()).isEqualTo(readerTime(start, LocalTime.MIN));
            assertThat(projection.getValidTo()).isEqualTo(readerTime(later, LocalTime.of(23, 59, 59)));
            assertThat(projection.getDeviceUserId()).isEqualTo("1");
            assertThat(projection.getUserStatus()).isZero();
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(4);
            assertThat(commands(flagged)).isEmpty();
            assertThat(commands(other)).extracting(DeviceSyncCommand::getType)
                    .contains(SyncCommandType.UPDATE_USER, SyncCommandType.UPDATE_VALIDITY);
        }
    }

    @Test
    void photoReplacePublishesAFaceRevisionAndReadsTheNewBytesBack() throws Exception {
        try (Harness harness = start("photo", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V4-FACE", "7401");
            String publicId = created.get("id").asString();
            awaitApplied(1);

            mockMvc.perform(multipart("/api/v1/members/" + publicId + "/face")
                            .file(new MockMultipartFile("file", "face.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg(Color.BLUE)))
                            .with(request -> {
                                request.setMethod("PUT");
                                return request;
                            })
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            awaitApplied(2);
            JsonNode report = harness.finish();

            assertThat(report.get("ackCount").asInt()).isEqualTo(2);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(2);
            assertThat(report.get("pendingAcks").asInt()).isZero();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1", "UpdateFace 1");
            JsonNode user = user(report, "1");
            DesiredMemberProjection projection = desiredMemberProjectionRepository
                    .findByDeviceIdAndMemberId(flagged, memberRepository.findByPublicId(publicId).orElseThrow().getId())
                    .orElseThrow();
            assertThat(user.get("faceSha256").asString()).isNotBlank();
            assertThat(projection.getFaceSha256()).isEqualToIgnoringCase(user.get("faceSha256").asString());
            assertThat(projection.getObservedFaceSha256()).isEqualToIgnoringCase(user.get("faceSha256").asString());
            assertThat(projection.getDeviceUserId()).isEqualTo("1");
            assertThat(projection.getRevision()).isEqualTo(2);
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(2);
            assertThat(commands(flagged)).isEmpty();
            assertThat(commands(other)).extracting(DeviceSyncCommand::getType).contains(SyncCommandType.UPSERT_FACE);
            assertNotPublicId(report, publicId);
        }
    }

    @Test
    void removeFromOneReaderLeavesTheMemberAndDoesNotWriteTheOther() throws Exception {
        try (Harness harness = start("remove", null)) {
            harness.awaitReady();
            JsonNode created = createOnReader("Asha", "Shah", "V5-REMOVE", "7501");
            String publicId = created.get("id").asString();
            awaitApplied(1);

            mockMvc.perform(post("/api/v1/members/" + publicId + "/device-sync/" + flaggedId + "/remove")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted());
            awaitApplied(2);
            JsonNode report = harness.finish();

            assertThat(report.get("ackCount").asInt()).isEqualTo(2);
            assertThat(report.get("appliedLocal").asLong()).isEqualTo(2);
            assertThat(report.get("users")).isEmpty();
            assertThat(report.get("writes")).extracting(JsonNode::asString)
                    .containsExactly("CreateUser 1", "InsertFace 1", "RemoveUser 1");
            assertThat(memberRepository.findByPublicId(publicId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
            DesiredMemberProjection projection = desiredMemberProjectionRepository
                    .findByDeviceIdAndMemberId(flagged, memberRepository.findByPublicId(publicId).orElseThrow().getId())
                    .orElseThrow();
            assertThat(projection.isPresentOnReader()).isFalse();
            assertThat(projection.getDeviceUserId()).isEqualTo("1");
            ReaderRevision cursor = revision();
            assertThat(cursor.getAppliedRevision()).isEqualTo(cursor.getDesiredRevision()).isEqualTo(2);
            assertThat(mapping(publicId).getDeviceUserId()).isEqualTo("1");
            assertThat(commands(flagged)).isEmpty();
            assertThat(commands(other)).extracting(DeviceSyncCommand::getType)
                    .doesNotContain(SyncCommandType.REMOVE_USER);
            assertNotPublicId(report, publicId);

            mockMvc.perform(post("/api/v1/members/" + publicId + "/device-sync/" + flaggedId + "/remove")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted());
            assertThat(revision().getDesiredRevision()).isEqualTo(2);
            assertThat(memberRepository.findByPublicId(publicId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(commands(flagged)).isEmpty();
            assertThat(commands(other)).extracting(DeviceSyncCommand::getType)
                    .doesNotContain(SyncCommandType.REMOVE_USER);
        }
    }

    @Test
    void reconnectDoesNotReplayOutboxCommandsForTheFlaggedReader() throws Exception {
        createOnReader("Asha", "Shah", "V6-RECONNECT", "7601");
        var gateway = gatewayRepository.findByPublicId(gatewayPublicId).orElseThrow();

        connected.onGatewayConnected(new GatewayConnectedEvent(gateway.getPublicId(), gateway.getId()));

        assertThat(commands(flagged)).isEmpty();
            assertThat(commands(other)).extracting(DeviceSyncCommand::getType)
                    .contains(SyncCommandType.RECONCILE_DEVICE);
    }

    @Test
    void oneReadersAcknowledgementDoesNotAdvanceTheOther() throws Exception {
        String sideId = createDevice("Side", true, gatewayPublicId);
        Long side = deviceRepository.findByPublicId(sideId).orElseThrow().getId();
        createOnReader("Asha", "Shah", "V7-A", "7701", flaggedId);
        JsonNode created = createOnReader("Bina", "Shah", "V7-B", "7702", sideId);

        ackDesired(sideId);

        assertThat(revision().getAppliedRevision()).isZero();
        ReaderRevision sideCursor = readerRevisionRepository.findByDeviceId(side).orElseThrow();
        assertThat(sideCursor.getAppliedRevision()).isEqualTo(sideCursor.getDesiredRevision()).isEqualTo(1);
        assertThat(projection(side, created.get("id").asString()).getReaderName()).isEqualTo("Bina Shah");

        ackDesired(flaggedId);

        sideCursor = readerRevisionRepository.findByDeviceId(side).orElseThrow();
        assertThat(sideCursor.getAppliedRevision()).isEqualTo(1);
        assertThat(revision().getAppliedRevision()).isEqualTo(1);
        assertThat(projection(side, created.get("id").asString()).getReaderName()).isEqualTo("Bina Shah");
        assertThat(projection(side, created.get("id").asString()).getRevision()).isEqualTo(1);
    }

    @Test
    void aPersonCreatedOnTheReaderWaitsWithoutAMember() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V8-OWNED", "7801");
        long members = memberRepository.count();
        String allocated = mapping(created.get("id").asString()).getDeviceUserId();

        postObservation("7", "Walk In", false);
        postObservation("7", "Walk In Again", false);
        postObservation(allocated, "Asha Shah", false);
        postObservation("8", "Gone", true);

        assertThat(memberRepository.count()).isEqualTo(members);
        assertThat(pendingEnrollmentRepository.findByDeviceId(flagged)).singleElement()
                .extracting(PendingEnrollment::getDeviceUserId, PendingEnrollment::getReviewStatus)
                .containsExactly("7", PendingEnrollment.PENDING);
        assertThat(deviceObservedUserRepository.findByDeviceIdAndDeviceUserId(flagged, "7").orElseThrow())
                .extracting(row -> row.getDeviceUserId(), row -> row.getReaderName())
                .containsExactly("7", "Walk In Again");
        assertThat(memberDeviceMappingRepository.findByDeviceIdAndDeviceUserId(flagged, "7")).isEmpty();
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, allocated)).isEmpty();

        JsonNode imported = readJson(mockMvc.perform(post("/api/v1/devices/" + flaggedId + "/import-users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(imported.get("created").asInt()).isZero();
        assertThat(memberRepository.count()).isEqualTo(members);
    }

    @Test
    void aReaderNameEditStaysAReviewItemAndTheMemberMatchesTheBaseline() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V9-NAME", "7901");
        String memberId = created.get("id").asString();
        ackDesired(flaggedId);
        long revision = revision().getDesiredRevision();
        String allocated = mapping(memberId).getDeviceUserId();

        postReaderEdit(allocated, "Asha Reader", "ADMIN");
        postReaderEdit(allocated, "Asha Reader", "ADMIN");

        var member = memberRepository.findByPublicId(memberId).orElseThrow();
        assertThat(member.getFullName()).isEqualTo("Asha Shah");
        assertThat(member.getDeviceAuthority()).isEqualTo(DeviceAuthority.USER);
        DeviceReviewItem item = deviceReviewItemRepository.findByDeviceId(flagged).stream()
                .filter(row -> allocated.equals(row.getDeviceUserId()))
                .toList()
                .get(0);
        assertThat(deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, allocated)).isPresent();
        assertThat(deviceReviewItemRepository.findByDeviceId(flagged)).singleElement()
                .extracting(DeviceReviewItem::getDeviceUserId, DeviceReviewItem::getBaselineName,
                        DeviceReviewItem::getServerName, DeviceReviewItem::getReaderName,
                        DeviceReviewItem::getReaderAuthority, DeviceReviewItem::getServerAuthority)
                .containsExactly(allocated, "Asha Shah", "Asha Shah", "Asha Reader", "ADMIN", "Customer");
        assertThat(item.getBaselineName()).isEqualTo(member.getFullName());
        assertThat(projection(flagged, memberId).getAuthority()).isEqualTo("Customer");
        assertThat(projection(flagged, memberId).getRevision()).isEqualTo(revision);
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, allocated)).isEmpty();
    }

    @Test
    void aReaderEditWithoutABaselineUsesTheDesiredRecord() throws Exception {
        JsonNode created = createOnReader("A", "", "V9-BASE", "7902");
        String memberId = created.get("id").asString();
        String allocated = mapping(memberId).getDeviceUserId();
        assertThat(deviceReaderBaselineRepository.findByDeviceId(flagged)).isEmpty();
        assertThat(projection(flagged, memberId).getReaderName()).isEqualTo("A");

        postReaderEdit(allocated, "B", "Customer");
        DeviceReviewItem first = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, allocated)
                .orElseThrow();

        postReaderEdit(allocated, "B", "Customer");

        DeviceReviewItem second = deviceReviewItemRepository.findByDeviceIdAndDeviceUserId(flagged, allocated)
                .orElseThrow();
        assertThat(deviceReviewItemRepository.findByDeviceId(flagged)).singleElement()
                .extracting(DeviceReviewItem::getPublicId, DeviceReviewItem::getBaselineName,
                        DeviceReviewItem::getServerName, DeviceReviewItem::getReaderName)
                .containsExactly(first.getPublicId(), "A", "A", "B");
        assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getFullName()).isEqualTo("A");
        assertThat(projection(flagged, memberId).getReaderName()).isEqualTo("A");
        assertThat(deviceReaderBaselineRepository.findByDeviceId(flagged)).isEmpty();
    }

    @Test
    void twoReadersKeepBothNamesOnOneConflict() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V10-TWO", "8001");
        String memberId = created.get("id").asString();
        Member member = memberRepository.findByPublicId(memberId).orElseThrow();
        String sideId = createDevice("Side", true, gatewayPublicId);
        Device side = deviceRepository.findByPublicId(sideId).orElseThrow();
        MemberFace face = memberFaceRepository.findByMemberId(member.getId()).orElseThrow();
        desiredProjection.write(member, side, face);
        String onEntrance = mapping(memberId).getDeviceUserId();
        String onSide = memberDeviceMappingRepository.findByDeviceIdAndMemberId(side.getId(), member.getId())
                .orElseThrow().getDeviceUserId();
        long entranceRevision = projection(flagged, memberId).getRevision();
        long sideRevision = projection(side.getId(), memberId).getRevision();

        postReaderEdit(flaggedId, onEntrance, "Left", "Customer");
        DeviceReviewItem opened = deviceReviewItemRepository.findByMemberId(member.getId()).get(0);

        postReaderEdit(sideId, onSide, "Right", "Customer");

        assertThat(deviceReviewItemRepository.findByMemberId(member.getId())).singleElement()
                .extracting(DeviceReviewItem::getPublicId, DeviceReviewItem::getBaselineName,
                        DeviceReviewItem::getServerName)
                .containsExactly(opened.getPublicId(), "Asha Shah", "Asha Shah");
        assertThat(deviceReviewSnapshotRepository.findByReviewItemId(opened.getId()))
                .extracting(DeviceReviewSnapshot::getDeviceId, DeviceReviewSnapshot::getReaderName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(flagged, "Left"),
                        org.assertj.core.groups.Tuple.tuple(side.getId(), "Right"));
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getFullName()).isEqualTo("Asha Shah");
        assertThat(projection(flagged, memberId).getReaderName()).isEqualTo("Asha Shah");
        assertThat(projection(side.getId(), memberId).getReaderName()).isEqualTo("Asha Shah");
        assertThat(projection(flagged, memberId).getRevision()).isEqualTo(entranceRevision);
        assertThat(projection(side.getId(), memberId).getRevision()).isEqualTo(sideRevision);
    }

    @Test
    void aTrustedDisappearanceKeepsTheMemberAndOneReviewItem() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V11-GONE", "8101");
        String memberId = created.get("id").asString();
        Member member = memberRepository.findByPublicId(memberId).orElseThrow();
        String sideId = createDevice("Side", true, gatewayPublicId);
        Device side = deviceRepository.findByPublicId(sideId).orElseThrow();
        MemberFace face = memberFaceRepository.findByMemberId(member.getId()).orElseThrow();
        desiredProjection.write(member, side, face);
        String onEntrance = mapping(memberId).getDeviceUserId();
        long entranceRevision = projection(flagged, memberId).getRevision();
        long sideRevision = projection(side.getId(), memberId).getRevision();
        long removes = deviceSyncCommandRepository.findAll().stream()
                .filter(command -> command.getType() == SyncCommandType.REMOVE_USER)
                .count();

        postAbsence(flaggedId, onEntrance);
        DeviceReviewItem opened = deviceReviewItemRepository
                .findByDeviceIdAndDeviceUserId(flagged, onEntrance)
                .orElseThrow();

        postAbsence(flaggedId, onEntrance);

        DeviceReviewItem again = deviceReviewItemRepository
                .findByDeviceIdAndDeviceUserId(flagged, onEntrance)
                .orElseThrow();
        assertThat(again.getPublicId()).isEqualTo(opened.getPublicId());
        assertThat(again.isReaderAbsent()).isTrue();
        assertThat(again.getBaselineName()).isEqualTo("Asha Shah");
        assertThat(deviceReviewItemRepository.findByMemberId(member.getId())).hasSize(1);
        assertThat(deviceReviewItemRepository.findByDeviceId(side.getId())).isEmpty();
        Member kept = memberRepository.findByPublicId(memberId).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(kept.getFullName()).isEqualTo("Asha Shah");
        assertThat(projection(flagged, memberId).isPresentOnReader()).isTrue();
        assertThat(projection(flagged, memberId).getRevision()).isEqualTo(entranceRevision);
        assertThat(projection(side.getId(), memberId).isPresentOnReader()).isTrue();
        assertThat(projection(side.getId(), memberId).getRevision()).isEqualTo(sideRevision);
        assertThat(projection(side.getId(), memberId).getReaderName()).isEqualTo("Asha Shah");
        assertThat(deviceSyncCommandRepository.findAll().stream()
                .filter(command -> command.getType() == SyncCommandType.REMOVE_USER)
                .count()).isEqualTo(removes);
    }

    @Test
    void aBadListCreatesNoDisappearanceAndTheNextTrustedListStillDoes() throws Exception {
        JsonNode created = createOnReader("Asha", "Shah", "V12-BAD", "8201");
        String memberId = created.get("id").asString();
        String onEntrance = mapping(memberId).getDeviceUserId();
        long revision = projection(flagged, memberId).getRevision();
        long removes = deviceSyncCommandRepository.findAll().stream()
                .filter(command -> command.getType() == SyncCommandType.REMOVE_USER)
                .count();

        postRoster(flaggedId, 1, "[{\"deviceUserId\":\"9\",\"name\":\"Still Here\",\"deleted\":false}]");
        DeviceReviewItem opened = deviceReviewItemRepository
                .findByDeviceIdAndDeviceUserId(flagged, onEntrance)
                .orElseThrow();
        assertThat(opened.isReaderAbsent()).isTrue();

        postRoster(flaggedId, 2, "[{\"deviceUserId\":\"8\",\"name\":\"Short\",\"deleted\":false}]");
        assertThat(deviceReviewItemRepository.findByDeviceId(flagged)).singleElement()
                .extracting(DeviceReviewItem::getPublicId)
                .isEqualTo(opened.getPublicId());
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "8")).isEmpty();
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);

        postRoster(flaggedId, 0, "[]");
        assertThat(deviceReviewItemRepository.findByDeviceId(flagged)).singleElement()
                .extracting(DeviceReviewItem::getPublicId)
                .isEqualTo(opened.getPublicId());
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);

        postRoster(flaggedId, 1,
                "[{\"deviceUserId\":\"8\",\"name\":\"A\",\"deleted\":false},{\"deviceUserId\":\"10\",\"name\":\"B\",\"deleted\":false}]");
        assertThat(deviceReviewItemRepository.findByDeviceId(flagged)).singleElement()
                .extracting(DeviceReviewItem::getPublicId)
                .isEqualTo(opened.getPublicId());
        assertThat(pendingEnrollmentRepository.findByDeviceIdAndDeviceUserId(flagged, "10")).isEmpty();
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getFullName()).isEqualTo("Asha Shah");

        postRoster(flaggedId, 1, "[{\"deviceUserId\":\"9\",\"name\":\"Still Here\",\"deleted\":false}]");

        DeviceReviewItem again = deviceReviewItemRepository
                .findByDeviceIdAndDeviceUserId(flagged, onEntrance)
                .orElseThrow();
        assertThat(again.getPublicId()).isEqualTo(opened.getPublicId());
        assertThat(again.isReaderAbsent()).isTrue();
        assertThat(memberRepository.findByPublicId(memberId).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(projection(flagged, memberId).isPresentOnReader()).isTrue();
        assertThat(projection(flagged, memberId).getRevision()).isEqualTo(revision);
        assertThat(deviceSyncCommandRepository.findAll().stream()
                .filter(command -> command.getType() == SyncCommandType.REMOVE_USER)
                .count()).isEqualTo(removes);
    }

    private void postRoster(String devicePublicId, int announcedTotal, String users) throws Exception {
        String payload = "{\"announcedTotal\":%d,\"users\":%s}".formatted(announcedTotal, users);
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, devicePublicId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private void postAbsence(String devicePublicId, String deviceUserId) throws Exception {
        String payload = """
                {"deviceUserId":"%s","deleted":true,"isNew":false}
                """.formatted(deviceUserId);
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, devicePublicId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private void postReaderEdit(String deviceUserId, String name, String authority) throws Exception {
        postReaderEdit(flaggedId, deviceUserId, name, authority);
    }

    private void postReaderEdit(String devicePublicId, String deviceUserId, String name, String authority)
            throws Exception {
        String payload = """
                {"deviceUserId":"%s","name":"%s","authority":"%s","isNew":false,"deleted":false,\
                "deviceChangedAt":"2099-01-01T00:00:00Z"}
                """.formatted(deviceUserId, name, authority);
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, devicePublicId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private void postObservation(String deviceUserId, String name, boolean deleted) throws Exception {
        String payload = """
                {"deviceUserId":"%s","name":"%s","isNew":true,"deleted":%s}
                """.formatted(deviceUserId, name, deleted);
        String envelope = """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"DEVICE_USER_CHANGED","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayPublicId, flaggedId,
                UUID.randomUUID(), payload);
        mockMvc.perform(post("/internal/gateway/messages")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isOk());
    }

    private static String readerTime(LocalDate day, LocalTime time) {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        return java.time.OffsetDateTime.of(day, time, zone.getRules().getOffset(day.atTime(time)))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    private void assertNotPublicId(JsonNode report, String publicId) {
        assertThat(publicId).isNotBlank();
        assertThat(report.get("createdId").asString()).isNotEqualTo(publicId);
        assertThat(report.get("pullTranscript").asString()).doesNotContain(publicId);
        assertThat(report.get("legacyDispatched").asBoolean()).isFalse();
        for (JsonNode write : report.get("writes")) {
            assertThat(write.asString()).doesNotContain(publicId);
        }
        for (JsonNode user : report.get("users")) {
            assertThat(user.get("id").asString()).isNotEqualTo(publicId);
            assertThat(user.get("name").asString()).doesNotContain(publicId);
            if (!user.get("nameEx").isNull()) {
                assertThat(user.get("nameEx").asString()).doesNotContain(publicId);
            }
        }
        if (!report.get("occupiedId").isNull()) {
            assertThat(report.get("occupiedId").asString()).isNotEqualTo(publicId);
        }
    }

    private static JsonNode user(JsonNode report, String id) {
        for (JsonNode user : report.get("users")) {
            if (id.equals(user.get("id").asString())) {
                return user;
            }
        }
        throw new AssertionError("Reader has no user " + id + " in " + report);
    }

    private Harness start(String mode, String occupy) throws Exception {
        Path result = Files.createTempFile("v1-result-", ".json");
        Path journal = Files.createTempDirectory("v1-journal-").resolve("reader.sqlite");
        List<String> command = new ArrayList<>();
        command.add(dotnet());
        command.add(harnessDll.toString());
        command.add("--base");
        command.add("http://127.0.0.1:" + port);
        command.add("--token");
        command.add(gatewayToken);
        command.add("--device");
        command.add(flaggedId);
        command.add("--mode");
        command.add(mode);
        command.add("--journal");
        command.add(journal.toString());
        command.add("--result");
        command.add(result.toString());
        if (occupy != null) {
            command.add("--occupy");
            command.add(occupy);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("DOTNET_ROOT", dotnetHome());
        builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        builder.environment().put("DOTNET_NOLOGO", "1");
        builder.environment().put("PATH", dotnetHome() + ":" + builder.environment().getOrDefault("PATH", ""));
        Process process = builder.start();
        return new Harness(process, result);
    }

    private ReaderRevision revision() {
        return readerRevisionRepository.findByDeviceId(flagged).orElseThrow();
    }

    private void awaitApplied(long revision) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        ReaderRevision cursor = null;
        while (System.nanoTime() < deadline) {
            cursor = readerRevisionRepository.findByDeviceId(flagged).orElse(null);
            if (cursor != null && cursor.getAppliedRevision() >= revision) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Applied revision did not reach " + revision + ": " + cursor);
    }

    private void ackDesired(String deviceId) throws Exception {
        JsonNode page = readJson(mockMvc.perform(get("/internal/gateway/desired")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .param("deviceId", deviceId)
                        .param("after", "0"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode item = page.get("items").get(0);
        String nameEx = item.get("nameEx").isNull() ? "null" : "\"" + item.get("nameEx").asString() + "\"";
        String ack = """
                {"deviceId":"%s","revision":%d,"deviceUserId":"%s","name":"%s","nameEx":%s,"userStatus":%d,"validFrom":"%s","validTo":"%s","faceSha256":"%s","present":true}
                """.formatted(deviceId, item.get("revision").asLong(), item.get("deviceUserId").asString(),
                item.get("name").asString(), nameEx, item.get("userStatus").asInt(),
                item.get("validFrom").asString(), item.get("validTo").asString(), item.get("faceSha256").asString());
        mockMvc.perform(post("/internal/gateway/desired/ack")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ack))
                .andExpect(status().isOk());
    }

    private DesiredMemberProjection projection(Long deviceId, String memberPublicId) {
        return desiredMemberProjectionRepository.findByDeviceIdAndMemberId(
                deviceId, memberRepository.findByPublicId(memberPublicId).orElseThrow().getId()).orElseThrow();
    }

    private MemberDeviceMapping mapping(String memberPublicId) {
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(
                flagged, memberRepository.findByPublicId(memberPublicId).orElseThrow().getId()).orElseThrow();
    }

    private List<DeviceSyncCommand> commands(Long deviceId) {
        return deviceSyncCommandRepository.findAll().stream()
                .filter(command -> deviceId.equals(command.getDeviceId()))
                .toList();
    }

    private JsonNode createOnReader(String firstName, String lastName, String code, String serial) throws Exception {
        return createOnReader(firstName, lastName, code, serial, flaggedId);
    }

    private JsonNode createOnReader(
            String firstName, String lastName, String code, String serial, String readerId) throws Exception {
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

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static byte[] jpeg() throws Exception {
        return jpeg(Color.RED);
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

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isDirectory(dir.resolve("gateway"))) {
            return dir;
        }
        if (dir.getParent() != null && Files.isDirectory(dir.getParent().resolve("gateway"))) {
            return dir.getParent();
        }
        throw new IllegalStateException("Cannot find the gateway project from " + dir);
    }

    private static String dotnet() {
        return dotnetHome() + "/dotnet";
    }

    private static String dotnetHome() {
        return System.getProperty("user.home") + "/.dotnet";
    }

    private static String readQuietly(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private final class Harness implements AutoCloseable {
        private final Process process;
        private final Path result;
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder errors = new StringBuilder();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final Thread outputThread;
        private final Thread errorThread;

        private Harness(Process process, Path result) {
            this.process = process;
            this.result = result;
            this.outputThread = new Thread(() -> collect(process.getInputStream(), output, ready), "v1-harness-out");
            this.errorThread = new Thread(() -> collect(process.getErrorStream(), errors, null), "v1-harness-err");
            outputThread.start();
            errorThread.start();
        }

        private void awaitReady() throws Exception {
            if (!ready.await(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Harness did not connect\n" + output + "\n" + errors);
            }
        }

        private JsonNode finish() throws Exception {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Harness timed out\n" + output + "\n" + errors);
            }
            outputThread.join(5_000);
            errorThread.join(5_000);
            String body = Files.exists(result) ? Files.readString(result) : output.toString();
            if (process.exitValue() != 0) {
                throw new AssertionError("Harness failed (" + process.exitValue() + ")\n" + body + "\n" + errors);
            }
            return readJson(body);
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void collect(InputStream stream, StringBuilder into, CountDownLatch ready) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                into.append(line).append('\n');
                if (ready != null && "READY".equals(line)) {
                    ready.countDown();
                }
            }
        } catch (Exception ex) {
            into.append(ex).append('\n');
        }
    }
}
