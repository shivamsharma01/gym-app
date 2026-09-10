package com.example.gym.support;

import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
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
    protected PasswordEncoder passwordEncoder;

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
