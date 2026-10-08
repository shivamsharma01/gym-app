package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.domain.DesiredMemberProjection;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
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
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

    private String token;
    private String gatewayToken;
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
        String gatewayId = readJson(createdGateway).get("id").asString();
        gatewayToken = enroll(gatewayId, readJson(createdGateway).get("token").asString());

        flaggedId = createDevice("Entrance", true, gatewayId);
        flagged = deviceRepository.findByPublicId(flaggedId).orElseThrow().getId();
        other = deviceRepository.findByPublicId(createDevice("Exit", false, gatewayId)).orElseThrow().getId();
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
        String member = """
                {"firstName":"%s","lastName":"%s","memberCode":"%s","serialNumber":"%s"}
                """.formatted(firstName, lastName, code, serial);
        MvcResult result = mockMvc.perform(multipart("/api/v1/members")
                        .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                                member.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("face", "face.jpg", MediaType.IMAGE_JPEG_VALUE, jpeg()))
                        .param("readerId", flaggedId)
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
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
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
