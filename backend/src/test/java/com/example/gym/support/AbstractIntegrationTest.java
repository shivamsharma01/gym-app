package com.example.gym.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.AttendanceSyncCursorRepository;
import com.example.gym.device.repo.DeviceRepository;
import com.example.gym.device.repo.DeviceSyncCommandRepository;
import com.example.gym.device.repo.DesiredMemberProjectionRepository;
import com.example.gym.device.repo.DeviceObservedUserRepository;
import com.example.gym.device.repo.DeviceReaderBaselineRepository;
import com.example.gym.device.repo.DeviceReviewItemRepository;
import com.example.gym.device.repo.DeviceReviewSnapshotRepository;
import com.example.gym.device.repo.PendingEnrollmentRepository;
import com.example.gym.device.repo.GatewayMessageDedupeRepository;
import com.example.gym.device.repo.GatewayRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.ReaderBlockedUserRepository;
import com.example.gym.device.repo.ReaderRevisionRepository;
import com.example.gym.device.repo.SecurityEventRepository;
import com.example.gym.enquiry.EnquiryRepository;
import com.example.gym.face.GatewayFaceUploadRepository;
import com.example.gym.face.MemberFaceRepository;
import com.example.gym.member.Member;
import com.example.gym.member.MemberRepository;
import com.example.gym.membership.MembershipRepository;
import com.example.gym.notification.AnnouncementRepository;
import com.example.gym.notification.NotificationTemplateRepository;
import com.example.gym.notification.OutboundNotificationRepository;
import com.example.gym.payment.PaymentRepository;
import com.example.gym.plan.MembershipPlanRepository;
import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import com.example.gym.settings.GymProfileRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests. Boots the full application context against a real MySQL container
 * (schema created by Flyway) so security, JWT, RBAC and tenant isolation are exercised end-to-end.
 *
 * <p>Uses the singleton-container pattern: the container is started once in a static initializer
 * and shared across every test class (reaped by Ryuk at JVM exit). We deliberately do NOT use
 * {@code @Testcontainers}/{@code @Container}, which would stop the static container after the first
 * test class and break subsequent classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    protected static final String DEFAULT_PASSWORD = "Password123!";

    /** Server-wide views such as InnoDB lock waits need a privilege the application user lacks. */
    protected static java.sql.Connection rootConnection() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected TenantRepository tenantRepository;

    @Autowired
    protected AdminUserRepository userRepository;

    @Autowired
    protected RoleRepository roleRepository;

    @Autowired
    protected MembershipPlanRepository planRepository;

    @Autowired
    protected MemberRepository memberRepository;

    @Autowired
    protected MembershipRepository membershipRepository;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected DeviceSyncCommandRepository deviceSyncCommandRepository;

    @Autowired
    protected AttendanceEventRepository attendanceEventRepository;

    @Autowired
    protected AttendanceSyncCursorRepository attendanceSyncCursorRepository;

    @Autowired
    protected SecurityEventRepository securityEventRepository;

    @Autowired
    protected MemberDeviceMappingRepository memberDeviceMappingRepository;

    @Autowired
    protected DeviceRepository deviceRepository;

    @Autowired
    protected GatewayRepository gatewayRepository;

    @Autowired
    protected GatewayMessageDedupeRepository gatewayMessageDedupeRepository;

    @Autowired
    protected DesiredMemberProjectionRepository desiredMemberProjectionRepository;

    @Autowired
    protected DeviceObservedUserRepository deviceObservedUserRepository;

    @Autowired
    protected PendingEnrollmentRepository pendingEnrollmentRepository;

    @Autowired
    protected DeviceReaderBaselineRepository deviceReaderBaselineRepository;

    @Autowired
    protected DeviceReviewItemRepository deviceReviewItemRepository;

    @Autowired
    protected DeviceReviewSnapshotRepository deviceReviewSnapshotRepository;

    @Autowired
    protected ReaderRevisionRepository readerRevisionRepository;

    @Autowired
    protected ReaderBlockedUserRepository readerBlockedUserRepository;

    @Autowired
    protected EnquiryRepository enquiryRepository;

    @Autowired
    protected GymProfileRepository gymProfileRepository;

    @Autowired
    protected NotificationTemplateRepository notificationTemplateRepository;

    @Autowired
    protected OutboundNotificationRepository outboundNotificationRepository;

    @Autowired
    protected AnnouncementRepository announcementRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected MemberFaceRepository memberFaceRepository;

    @Autowired
    protected GatewayFaceUploadRepository gatewayFaceUploadRepository;

    /**
     * Clears all tenant data in FK-safe order (child -> parent). The container is shared across
     * every test class, so each class must start from a clean slate. Seeded roles/permissions are
     * left intact.
     */
    protected void resetDatabase() {
        memberFaceRepository.deleteAllInBatch();
        gatewayFaceUploadRepository.deleteAllInBatch();
        deviceSyncCommandRepository.deleteAllInBatch();
        attendanceEventRepository.deleteAllInBatch();
        attendanceSyncCursorRepository.deleteAllInBatch();
        securityEventRepository.deleteAllInBatch();
        deviceReviewSnapshotRepository.deleteAllInBatch();
        deviceReviewItemRepository.deleteAllInBatch();
        deviceReaderBaselineRepository.deleteAllInBatch();
        pendingEnrollmentRepository.deleteAllInBatch();
        deviceObservedUserRepository.deleteAllInBatch();
        desiredMemberProjectionRepository.deleteAllInBatch();
        readerBlockedUserRepository.deleteAllInBatch();
        readerRevisionRepository.deleteAllInBatch();
        gatewayMessageDedupeRepository.deleteAllInBatch();
        memberDeviceMappingRepository.deleteAllInBatch();
        deviceRepository.deleteAllInBatch();
        gatewayRepository.deleteAllInBatch();
        outboundNotificationRepository.deleteAllInBatch();
        notificationTemplateRepository.deleteAllInBatch();
        announcementRepository.deleteAllInBatch();
        enquiryRepository.deleteAllInBatch();
        gymProfileRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        membershipRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
        planRepository.deleteAllInBatch();
        userRepository.deleteAll();     // cascades to refresh_token / user_role via DB FKs
        tenantRepository.deleteAll();
    }

    /** Logs in with the shared default password and returns a bearer access token. */
    protected String tokenFor(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\""
                                + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).get("accessToken").asString();
    }

    protected JsonNode readJson(String body) {
        return jsonMapper.readTree(body);
    }

    /** Staff create with a face. Every reader of the gym that has a gateway gets a desired user. */
    protected ResultActions createMemberWithFace(String bearer, String memberJson) throws Exception {
        return mockMvc.perform(multipart("/api/v1/members")
                .file(new MockMultipartFile("member", "member.json", MediaType.APPLICATION_JSON_VALUE,
                        memberJson.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("face", "face.jpg", MediaType.IMAGE_JPEG_VALUE, faceJpeg()))
                .header("Authorization", "Bearer " + bearer));
    }

    /** The created member's public id. */
    protected String createMemberId(String bearer, String memberJson) throws Exception {
        return readJson(createMemberWithFace(bearer, memberJson)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    /** A member stored without the create flow, so no reader has them. The test places them itself. */
    protected Member saveMember(Long tenantId, String memberCode, String firstName, String lastName, String serial) {
        Member member = new Member(tenantId, memberCode, firstName);
        member.setLastName(lastName);
        member.setSerialNumber(serial);
        return memberRepository.save(member);
    }

    protected static byte[] faceJpeg() throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    protected Tenant createTenant(String name, String slug) {
        return tenantRepository.save(new Tenant(name, slug));
    }

    protected AdminUser createUser(Long tenantId, String username, String email, String roleName) {
        Role role = roleRepository.findByNameAndTenantIdIsNull(roleName)
                .orElseThrow(() -> new IllegalStateException("Missing system role: " + roleName));
        AdminUser user = new AdminUser(tenantId, username, email,
                passwordEncoder.encode(DEFAULT_PASSWORD), "Test " + username);
        user.setRoles(Set.of(role));
        return userRepository.save(user);
    }
}
