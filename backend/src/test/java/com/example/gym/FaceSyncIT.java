package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.audit.AuditLog;
import com.example.gym.audit.AuditLogRepository;
import com.example.gym.device.GatewayMessageService;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SyncCommandState;
import com.example.gym.device.domain.SyncCommandType;
import com.example.gym.face.FaceSource;
import com.example.gym.face.MemberFace;
import com.example.gym.member.Member;
import com.example.gym.member.MemberCreationSource;
import com.example.gym.membership.Membership;
import com.example.gym.membership.MembershipStatus;
import com.example.gym.support.AbstractIntegrationTest;
import com.example.gym.tenant.Tenant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Two-way member + face sync: React photo upload fans out to every device, devices report users
 * and faces back (DEVICE_USER_CHANGED), latest change wins, and echoes are ignored.
 */
class FaceSyncIT extends AbstractIntegrationTest {

    @Autowired
    private GatewayMessageService gatewayMessageService;

    @Autowired
    private AuditLogRepository auditLogRepository;

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
        Tenant tenant = createTenant("Face Gym", "face-gym");
        createUser(tenant.getId(), "face-admin", "face-admin@face.local", "GYM_ADMIN");
        token = tokenFor("face-admin");

        String createdGateway = postJson("/api/v1/gateways", "{\"name\":\"LAN-face\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        gatewayId = readJson(createdGateway).get("id").asString();
        String enrolled = mockMvc.perform(post("/internal/gateway/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"" + gatewayId + "\",\"enrollmentToken\":\""
                                + readJson(createdGateway).get("token").asString() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        gatewayToken = readJson(enrolled).get("credential").asString();

        entranceId = createDevice("Entrance", "ENTRANCE", "10.0.0.20");
        exitId = createDevice("Exit", "EXIT", "10.0.0.21");
        entrance = deviceRepository.findByPublicId(entranceId).orElseThrow().getId();
        exit = deviceRepository.findByPublicId(exitId).orElseThrow().getId();
    }

    @Test
    void photoUploadFansOutToEveryDeviceAndSupersedesOlderVersions() throws Exception {
        String memberId = createMember("Asha", "5001");

        uploadPhoto(memberId, jpeg(Color.RED, 400)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.source").value("MANUAL"));
        List<DeviceSyncCommand> v1 = faceCommands(SyncCommandType.UPSERT_FACE);
        assertThat(v1).extracting(DeviceSyncCommand::getDeviceId).containsExactlyInAnyOrder(entrance, exit);
        assertThat(v1).allMatch(c -> c.getPayload().contains("\"faceVersion\":1"));

        // Same image again: nothing new.
        uploadPhoto(memberId, jpeg(Color.RED, 400)).andExpect(jsonPath("$.version").value(1));
        assertThat(faceCommands(SyncCommandType.UPSERT_FACE)).hasSize(2);

        // Invalid image is rejected before anything is stored.
        uploadPhoto(memberId, "not an image".getBytes()).andExpect(status().isBadRequest());
        uploadPhoto(memberId, jpeg(Color.BLUE, 100)).andExpect(status().isBadRequest());

        uploadPhoto(memberId, jpeg(Color.GREEN, 400)).andExpect(jsonPath("$.version").value(2));
        assertThat(deviceSyncCommandRepository.findAllById(v1.stream().map(DeviceSyncCommand::getId).toList()))
                .allMatch(c -> c.getState() == SyncCommandState.CANCELLED);

        // The gateway can download only the current version.
        mockMvc.perform(get("/internal/gateway/faces/" + memberId + "/2")
                        .header("Authorization", "Bearer " + gatewayToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Face-Sha256"));
        mockMvc.perform(get("/internal/gateway/faces/" + memberId + "/1")
                        .header("Authorization", "Bearer " + gatewayToken))
                .andExpect(status().isNotFound());

        // A late result for the superseded v1 command does not mark v1 as on the device.
        gatewayMessageService.process(envelope(entranceId, "SYNC_RESULT", v1.getFirst().getCorrelationId(),
                "{\"ok\":true,\"faceVersion\":1}"));
        MemberDeviceMapping mapping = mapping(memberId, v1.getFirst().getDeviceId());
        assertThat(mapping.getFaceVersionSynced()).isNull();

        // The v2 result does.
        DeviceSyncCommand v2 = faceCommands(SyncCommandType.UPSERT_FACE).stream()
                .filter(c -> c.getState() == SyncCommandState.PENDING && c.getDeviceId().equals(entrance))
                .findFirst().orElseThrow();
        gatewayMessageService.process(envelope(entranceId, "SYNC_RESULT", v2.getCorrelationId(),
                "{\"ok\":true,\"faceVersion\":2}"));
        assertThat(mapping(memberId, entrance).getFaceVersionSynced()).isEqualTo(2);
        assertThat(mapping(memberId, entrance).getFaceSyncState().name()).isEqualTo("SYNCED");

        mockMvc.perform(get("/api/v1/members/" + memberId + "/device-sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.face.version").value(2))
                .andExpect(jsonPath("$.devices.length()").value(2));
    }

    @Test
    void userCreatedOnDeviceBecomesMemberAndFansOutOnlyToOtherDevices() throws Exception {
        String uploadId = uploadFromGateway(jpeg(Color.ORANGE, 300));
        deviceUserChanged(entranceId, """
                {"deviceUserId":"7001","name":"Ravi Kumar","frozen":false,
                 "validFrom":"2026-01-01T00:00:00.000Z","validTo":"2027-01-01T00:00:00.000Z",
                 "deviceChangedAt":"%s","isNew":true,"profileChanged":true,"faceChanged":true,
                 "faceRemoved":false,"faceUploadId":"%s"}
                """.formatted(Instant.now(), uploadId));

        Member member = memberRepository.findAll().stream()
                .filter(m -> "7001".equals(m.getMemberCode())).findFirst().orElseThrow();
        assertThat(member.getCreationSource()).isEqualTo(MemberCreationSource.DEVICE_IMPORT);
        assertThat(member.getFullName()).isEqualTo("Ravi Kumar");
        MemberFace face = memberFaceRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(face.getSource()).isEqualTo(FaceSource.DEVICE);
        assertThat(face.getSourceDeviceId()).isEqualTo(entrance);

        MemberDeviceMapping source = mapping(member.getPublicId(), entrance);
        assertThat(source.getSyncState().name()).isEqualTo("SYNCED");
        assertThat(source.getFaceVersionSynced()).isEqualTo(1);

        List<DeviceSyncCommand> commands = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> member.getId().equals(c.getMemberId())).toList();
        assertThat(commands).noneMatch(c -> c.getDeviceId().equals(entrance));
        assertThat(commands).filteredOn(c -> c.getDeviceId().equals(exit))
                .extracting(DeviceSyncCommand::getType)
                .contains(SyncCommandType.CREATE_USER, SyncCommandType.UPSERT_FACE);
    }

    @Test
    void latestFaceChangeWinsAndEchoesAreIgnored() throws Exception {
        String memberId = createMember("Meera", "5002");
        uploadPhoto(memberId, jpeg(Color.RED, 400)).andExpect(jsonPath("$.version").value(1));
        Long id = memberRepository.findByPublicId(memberId).orElseThrow().getId();

        // Older device change: the server keeps its face and re-sends it to that device.
        String older = uploadFromGateway(jpeg(Color.CYAN, 300));
        long before = deviceSyncCommandRepository.count();
        deviceUserChanged(exitId, faceChange("5002", "Meera", Instant.now().minus(1, ChronoUnit.HOURS), older));
        assertThat(memberFaceRepository.findByMemberId(id).orElseThrow().getFaceVersion()).isEqualTo(1);
        assertThat(newCommands(before)).extracting(DeviceSyncCommand::getType, DeviceSyncCommand::getDeviceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SyncCommandType.UPSERT_FACE, exit));

        // Newer device change: stored as v2 and pushed only to the other device.
        String newer = uploadFromGateway(jpeg(Color.MAGENTA, 300));
        before = deviceSyncCommandRepository.count();
        deviceUserChanged(exitId, faceChange("5002", "Meera", Instant.now(), newer));
        MemberFace face = memberFaceRepository.findByMemberId(id).orElseThrow();
        assertThat(face.getFaceVersion()).isEqualTo(2);
        assertThat(face.getSource()).isEqualTo(FaceSource.DEVICE);
        assertThat(mapping(memberId, exit).getFaceVersionSynced()).isEqualTo(2);
        assertThat(newCommands(before)).extracting(DeviceSyncCommand::getType, DeviceSyncCommand::getDeviceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SyncCommandType.UPSERT_FACE, entrance));

        // Echo: the entrance reports the same image it was sent. Nothing changes.
        String echo = uploadFromGateway(jpeg(Color.MAGENTA, 300));
        before = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, faceChange("5002", "Meera", Instant.now(), echo));
        assertThat(memberFaceRepository.findByMemberId(id).orElseThrow().getFaceVersion()).isEqualTo(2);
        assertThat(newCommands(before)).isEmpty();
        assertThat(mapping(memberId, entrance).getFaceVersionSynced()).isEqualTo(2);
    }

    @Test
    void latestNameChangeWins() throws Exception {
        String memberId = createMember("Kiran", "5003");
        Long id = memberRepository.findByPublicId(memberId).orElseThrow().getId();

        long before = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, nameChange("5003", "Old Name", Instant.now().minus(1, ChronoUnit.HOURS)));
        assertThat(memberRepository.findById(id).orElseThrow().getFullName()).isEqualTo("Kiran Test");
        assertThat(newCommands(before)).extracting(DeviceSyncCommand::getType)
                .contains(SyncCommandType.UPDATE_USER)
                .allMatch(t -> t != SyncCommandType.UPSERT_FACE);
        assertThat(newCommands(before)).allMatch(c -> c.getDeviceId().equals(entrance));

        before = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, nameChange("5003", "Kiran Sharma", Instant.now()));
        assertThat(memberRepository.findById(id).orElseThrow().getFullName()).isEqualTo("Kiran Sharma");
        assertThat(newCommands(before)).extracting(DeviceSyncCommand::getType, DeviceSyncCommand::getDeviceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SyncCommandType.UPDATE_USER, exit));
    }

    @Test
    void deletionOnDeviceDeactivatesMemberAndRemovesItFromEveryDevice() throws Exception {
        String memberId = createMember("Dev", "5004");
        Long id = memberRepository.findByPublicId(memberId).orElseThrow().getId();

        deviceUserChanged(entranceId, deleted("5004", Instant.now()));

        assertThat(memberRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("INACTIVE");
        assertThat(memberDeviceMappingRepository.findByMemberId(id)).isEmpty();
        List<DeviceSyncCommand> commands = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> id.equals(c.getMemberId())).toList();
        assertThat(commands).filteredOn(c -> c.getType() == SyncCommandType.REMOVE_USER)
                .extracting(DeviceSyncCommand::getDeviceId).containsExactlyInAnyOrder(entrance, exit);
        assertThat(commands).filteredOn(c -> c.getType() != SyncCommandType.REMOVE_USER)
                .allMatch(c -> c.getState() == SyncCommandState.CANCELLED);

        // A repeated report of the same deletion is a no-op.
        long before = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, deleted("5004", Instant.now()));
        assertThat(newCommands(before)).isEmpty();
    }

    @Test
    void deletionOlderThanTheServerChangeRecreatesTheUserEverywhereAndIsLogged() throws Exception {
        String memberId = createMember("Nia", "5005");
        Long id = memberRepository.findByPublicId(memberId).orElseThrow().getId();

        Instant deletedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant before = Instant.now();
        long count = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, deleted("5005", deletedAt));

        assertThat(memberRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(memberDeviceMappingRepository.findByMemberId(id)).hasSize(2);
        // The gateway already removed it from its devices, so both get it back, stamped now so the
        // gateway accepts it over its own (older) deletion.
        List<DeviceSyncCommand> created = newCommands(count).stream()
                .filter(c -> c.getType() == SyncCommandType.CREATE_USER).toList();
        assertThat(created).extracting(DeviceSyncCommand::getDeviceId).containsExactlyInAnyOrder(entrance, exit);
        assertThat(created).allMatch(c -> changeTime(c, "nameChangedAt").isAfter(before.minusSeconds(1)));
        assertThat(ignoredAudits(memberId)).anyMatch(d -> d.contains("Deletion on the device"));
    }

    @Test
    void commandsCarryTheChangeTimeOfTheFieldsTheyWrite() throws Exception {
        String memberId = createMember("Tara", "5007");
        Member member = memberRepository.findByPublicId(memberId).orElseThrow();
        DeviceSyncCommand create = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> member.getId().equals(c.getMemberId()) && c.getType() == SyncCommandType.CREATE_USER)
                .findFirst().orElseThrow();
        assertThat(changeTime(create, "nameChangedAt")).isEqualTo(member.getProfileChangedAt());

        uploadPhoto(memberId, jpeg(Color.RED, 400)).andExpect(status().isOk());
        Member reloaded = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(faceCommands(SyncCommandType.UPSERT_FACE))
                .allMatch(c -> changeTime(c, "faceChangedAt").equals(reloaded.getFaceChangedAt()));
    }

    @Test
    void commandSkippedByTheGatewayIsLoggedAndNotRetried() throws Exception {
        String memberId = createMember("Uma", "5008");
        Member member = memberRepository.findByPublicId(memberId).orElseThrow();
        DeviceSyncCommand create = deviceSyncCommandRepository.findAll().stream()
                .filter(c -> member.getId().equals(c.getMemberId()) && c.getType() == SyncCommandType.CREATE_USER
                        && c.getDeviceId().equals(entrance))
                .findFirst().orElseThrow();

        gatewayMessageService.process(envelope(entranceId, "SYNC_RESULT", create.getCorrelationId(),
                "{\"ok\":true,\"skipped\":true,\"reason\":\"name changed on device at a later time\"}"));

        DeviceSyncCommand after = deviceSyncCommandRepository.findById(create.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(SyncCommandState.SUCCEEDED);
        assertThat(after.getLastError()).startsWith("Skipped by gateway");
        assertThat(ignoredAudits(memberId)).anyMatch(d -> d.contains("name changed on device"));
    }

    @Test
    void userEnrolledOfflineWithAnotherMembersCodeBecomesASeparateFlaggedMember() throws Exception {
        String existingId = createMember("Neha", "5009");
        Member existing = memberRepository.findByPublicId(existingId).orElseThrow();
        String uploadId = uploadFromGateway(jpeg(Color.ORANGE, 300));
        Instant enrolledAt = Instant.now().minus(10, ChronoUnit.MINUTES);

        long count = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, """
                {"deviceUserId":"5009","name":"Vikram Rao","frozen":false,
                 "validFrom":"2026-01-01","validTo":"2027-01-01",
                 "deviceChangedAt":"%s","isNew":true,"profileChanged":true,"nameChanged":true,
                 "frozenChanged":true,"validityChanged":true,"faceChanged":true,
                 "faceRemoved":false,"faceUploadId":"%s"}
                """.formatted(enrolledAt, uploadId));

        Member reloaded = memberRepository.findById(existing.getId()).orElseThrow();
        assertThat(reloaded.getFullName()).isEqualTo("Neha Test");
        Member created = memberRepository.findAll().stream()
                .filter(m -> "Vikram Rao".equals(m.getFullName())).findFirst().orElseThrow();
        assertThat(created.getMemberCode()).isNotEqualTo("5009");
        assertThat(memberFaceRepository.findByMemberId(created.getId())).isPresent();
        assertThat(memberFaceRepository.findByMemberId(existing.getId())).isEmpty();

        List<DeviceSyncCommand> commands = newCommands(count);
        // The device user is written to every device under its new code...
        assertThat(commands).filteredOn(c -> created.getId().equals(c.getMemberId())
                        && c.getType() == SyncCommandType.CREATE_USER)
                .extracting(DeviceSyncCommand::getDeviceId).containsExactlyInAnyOrder(entrance, exit);
        // ...and the existing member replaces it under the old code, face removed, stamped now.
        assertThat(commands).filteredOn(c -> existing.getId().equals(c.getMemberId()))
                .extracting(DeviceSyncCommand::getType)
                .contains(SyncCommandType.CREATE_USER, SyncCommandType.DELETE_FACE);
        assertThat(reloaded.getProfileChangedAt()).isAfter(enrolledAt);

        assertThat(reconciliationConflictRepository.findAll())
                .anyMatch(c -> c.getConflictType().name().equals("DEVICE_CODE_CLASH")
                        && c.getDeviceUserId().equals("5009"));
        assertThat(auditLogRepository.findAll()).anyMatch(a -> a.getAction().equals("DEVICE_CODE_CLASH")
                && created.getPublicId().equals(a.getResourceId()));
    }

    @Test
    void staleEditOfADeletedMemberIsIgnoredAndRemovedAgain() throws Exception {
        String memberId = createMember("Arun", "5010");
        Long id = memberRepository.findByPublicId(memberId).orElseThrow().getId();
        deviceUserChanged(entranceId, deleted("5010", Instant.now()));

        long count = deviceSyncCommandRepository.count();
        deviceUserChanged(exitId, nameChange("5010", "Arun Old", Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(memberRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("INACTIVE");
        assertThat(newCommands(count)).extracting(DeviceSyncCommand::getType, DeviceSyncCommand::getDeviceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SyncCommandType.REMOVE_USER, exit));
        assertThat(ignoredAudits(memberId)).anyMatch(d -> d.contains("the member was deleted"));

        // A later edit on a device brings the member back everywhere.
        deviceUserChanged(exitId, nameChange("5010", "Arun Kumar", Instant.now()));
        Member back = memberRepository.findById(id).orElseThrow();
        assertThat(back.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(back.getFullName()).isEqualTo("Arun Kumar");
        assertThat(memberDeviceMappingRepository.findByMemberId(id)).hasSize(2);
    }

    @Test
    void faceRemovedOnDeviceIsRemovedEverywhereWhenNewer() throws Exception {
        String memberId = createMember("Isha", "5006");
        uploadPhoto(memberId, jpeg(Color.RED, 400)).andExpect(status().isOk());
        Long id = memberRepository.findByPublicId(memberId).orElseThrow().getId();

        // Older removal: server face is kept and re-sent to that device.
        long before = deviceSyncCommandRepository.count();
        deviceUserChanged(exitId, faceRemoved("5006", Instant.now().minus(1, ChronoUnit.HOURS)));
        assertThat(memberFaceRepository.findByMemberId(id)).isPresent();
        assertThat(newCommands(before)).allMatch(c -> c.getDeviceId().equals(exit))
                .extracting(DeviceSyncCommand::getType).contains(SyncCommandType.UPSERT_FACE);

        before = deviceSyncCommandRepository.count();
        deviceUserChanged(exitId, faceRemoved("5006", Instant.now()));
        assertThat(memberFaceRepository.findByMemberId(id)).isEmpty();
        assertThat(newCommands(before)).extracting(DeviceSyncCommand::getType, DeviceSyncCommand::getDeviceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SyncCommandType.DELETE_FACE, entrance));
    }

    @Test
    void validityAndFreezeEditedOnDeviceAreApplied() throws Exception {
        LocalDate today = LocalDate.now();
        deviceUserChanged(entranceId, access("7002", "Om Prakash", false, today.minusDays(10), today.plusDays(20),
                Instant.now(), true, true, true));
        Member member = memberRepository.findByTenantIdAndMemberCode(
                deviceRepository.findById(entrance).orElseThrow().getTenantId(), "7002").orElseThrow();

        long before = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, access("7002", "Om Prakash", false, today.minusDays(10), today.plusDays(60),
                Instant.now(), false, false, true));
        Membership membership = currentMembership(member.getId());
        assertThat(membership.getEndDate()).isEqualTo(today.plusDays(60));
        assertThat(newCommands(before)).extracting(DeviceSyncCommand::getDeviceId).contains(exit);

        before = deviceSyncCommandRepository.count();
        deviceUserChanged(entranceId, access("7002", "Om Prakash", true, today.minusDays(10), today.plusDays(60),
                Instant.now(), false, true, false));
        assertThat(currentMembership(member.getId()).getStatus()).isEqualTo(MembershipStatus.FROZEN);
        assertThat(newCommands(before)).filteredOn(c -> c.getDeviceId().equals(exit))
                .extracting(DeviceSyncCommand::getType).contains(SyncCommandType.DISABLE_USER);

        deviceUserChanged(entranceId, access("7002", "Om Prakash", false, today.minusDays(10), today.plusDays(60),
                Instant.now(), false, true, false));
        assertThat(currentMembership(member.getId()).getStatus()).isEqualTo(MembershipStatus.ACTIVE);
    }

    @Test
    void serverWinsWhenItsAccessChangeIsNewerAndUntouchedFieldsAreIgnored() throws Exception {
        LocalDate today = LocalDate.now();
        Instant created = Instant.now();
        deviceUserChanged(entranceId, access("7003", "Lata", false, today.minusDays(5), today.plusDays(30),
                created, true, true, true));
        Member member = memberRepository.findByTenantIdAndMemberCode(
                deviceRepository.findById(entrance).orElseThrow().getTenantId(), "7003").orElseThrow();

        // Offline freeze made before the server's last access change: server keeps its state.
        long before = deviceSyncCommandRepository.count();
        deviceUserChanged(exitId, access("7003", "Lata", true, today.minusDays(5), today.plusDays(30),
                created.minus(1, ChronoUnit.HOURS), false, true, false));
        assertThat(currentMembership(member.getId()).getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(newCommands(before)).allMatch(c -> c.getDeviceId().equals(exit))
                .extracting(DeviceSyncCommand::getType).contains(SyncCommandType.UPDATE_USER);

        // Name-only change: the device's (stale) frozen flag and dates are not applied.
        deviceUserChanged(exitId, access("7003", "Lata Devi", true, today.minusDays(5), today.plusDays(99),
                Instant.now(), true, false, false));
        Member reloaded = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(reloaded.getFullName()).isEqualTo("Lata Devi");
        Membership membership = currentMembership(member.getId());
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membership.getEndDate()).isEqualTo(today.plusDays(30));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private List<String> ignoredAudits(String memberPublicId) {
        return auditLogRepository.findAll().stream()
                .filter(a -> "SYNC_CHANGE_IGNORED".equals(a.getAction()) && memberPublicId.equals(a.getResourceId()))
                .map(AuditLog::getDetails)
                .toList();
    }

    private Instant changeTime(DeviceSyncCommand command, String field) {
        try {
            return Instant.parse(readJson(command.getPayload()).get(field).asString());
        } catch (Exception ex) {
            throw new AssertionError("No " + field + " in " + command.getPayload(), ex);
        }
    }

    private Membership currentMembership(Long memberId) {
        return membershipRepository.findByMemberIdAndDeletedFalseOrderByStartDateDesc(memberId).getFirst();
    }

    private static String deleted(String userId, Instant at) {
        return """
                {"deviceUserId":"%s","deleted":true,"deviceChangedAt":"%s"}
                """.formatted(userId, at);
    }

    private static String faceRemoved(String userId, Instant at) {
        return """
                {"deviceUserId":"%s","name":"x","frozen":false,"deviceChangedAt":"%s","isNew":false,
                 "profileChanged":false,"nameChanged":false,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":true,"faceRemoved":true}
                """.formatted(userId, at);
    }

    private static String access(String userId, String name, boolean frozen, LocalDate from, LocalDate to,
                                 Instant at, boolean nameChanged, boolean frozenChanged, boolean validityChanged) {
        return """
                {"deviceUserId":"%s","name":"%s","frozen":%s,"validFrom":"%s","validTo":"%s",
                 "deviceChangedAt":"%s","isNew":false,"profileChanged":true,"nameChanged":%s,
                 "frozenChanged":%s,"validityChanged":%s,"faceChanged":false,"faceRemoved":false}
                """.formatted(userId, name, frozen, from, to, at, nameChanged, frozenChanged, validityChanged);
    }

    private String createDevice(String name, String role, String host) throws Exception {
        return readJson(postJson("/api/v1/devices",
                "{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"host\":\"" + host
                        + "\",\"port\":37777,\"gatewayId\":\"" + gatewayId + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private String createMember(String firstName, String code) throws Exception {
        return readJson(postJson("/api/v1/members",
                "{\"firstName\":\"" + firstName + "\",\"lastName\":\"Test\",\"memberCode\":\"" + code + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private ResultActions uploadPhoto(String memberId, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/members/" + memberId + "/face")
                .file(new MockMultipartFile("file", "face.jpg", "image/jpeg", bytes))
                .header("Authorization", "Bearer " + token));
    }

    private String uploadFromGateway(byte[] bytes) throws Exception {
        String body = mockMvc.perform(post("/internal/gateway/faces")
                        .header("Authorization", "Bearer " + gatewayToken)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(bytes))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readJson(body).get("uploadId").asString();
    }

    private void deviceUserChanged(String devicePublicId, String payload) {
        gatewayMessageService.process(envelope(devicePublicId, "DEVICE_USER_CHANGED",
                UUID.randomUUID().toString(), payload));
    }

    private static String faceChange(String userId, String name, Instant at, String uploadId) {
        return """
                {"deviceUserId":"%s","name":"%s","frozen":false,"deviceChangedAt":"%s","isNew":false,
                 "profileChanged":false,"faceChanged":true,"faceRemoved":false,"faceUploadId":"%s"}
                """.formatted(userId, name, at, uploadId);
    }

    private static String nameChange(String userId, String name, Instant at) {
        return """
                {"deviceUserId":"%s","name":"%s","frozen":false,"deviceChangedAt":"%s","isNew":false,
                 "profileChanged":true,"nameChanged":true,"frozenChanged":false,"validityChanged":false,
                 "faceChanged":false,"faceRemoved":false}
                """.formatted(userId, name, at);
    }

    private List<DeviceSyncCommand> faceCommands(SyncCommandType type) {
        return deviceSyncCommandRepository.findAll().stream().filter(c -> c.getType() == type).toList();
    }

    private List<DeviceSyncCommand> newCommands(long previousCount) {
        return deviceSyncCommandRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .skip(previousCount)
                .toList();
    }

    private MemberDeviceMapping mapping(String memberPublicId, Long deviceId) {
        Long memberId = memberRepository.findByPublicId(memberPublicId).orElseThrow().getId();
        return memberDeviceMappingRepository.findByDeviceIdAndMemberId(deviceId, memberId).orElseThrow();
    }

    private static byte[] jpeg(Color color, int side) throws Exception {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, side, side);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String envelope(String devicePublicId, String type, String correlationId, String payloadJson) {
        return """
                {"messageId":"%s","timestamp":"%s","gatewayId":"%s","deviceId":"%s",\
                "type":"%s","correlationId":"%s","payload":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), gatewayId, devicePublicId, type,
                correlationId, payloadJson);
    }
}
