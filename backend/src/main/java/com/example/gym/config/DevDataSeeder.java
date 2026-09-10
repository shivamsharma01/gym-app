package com.example.gym.config;

import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a demo tenant and one account per role for local development only. Idempotent and strictly
 * gated to the {@code dev} profile — never runs in test/prod. The default password is a placeholder
 * that must be changed; it is logged (dev-only) purely for convenience.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String DEFAULT_PASSWORD = "ChangeMe123!";

    private final TenantRepository tenantRepository;
    private final AdminUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(TenantRepository tenantRepository, AdminUserRepository userRepository,
                         RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Tenant tenant = tenantRepository.findBySlug("downtown-fitness")
                .orElseGet(() -> tenantRepository.save(new Tenant("Downtown Fitness", "downtown-fitness")));

        seedUser(null, "superadmin", "superadmin@platform.local", "Platform Super Admin", "SUPER_ADMIN");
        seedUser(tenant.getId(), "owner", "owner@demo.local", "Demo Owner", "GYM_OWNER");
        seedUser(tenant.getId(), "admin", "admin@demo.local", "Demo Admin", "GYM_ADMIN");
        seedUser(tenant.getId(), "staff", "staff@demo.local", "Demo Staff", "STAFF");
        seedUser(tenant.getId(), "frontdesk", "frontdesk@demo.local", "Demo Front Desk", "FRONT_DESK");

        log.warn("DEV data seeded. Default password for all seeded accounts is '{}'. "
                + "Change it before any non-local use.", DEFAULT_PASSWORD);
    }

    private void seedUser(Long tenantId, String username, String email, String fullName, String roleName) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        Role role = roleRepository.findByNameAndTenantIdIsNull(roleName)
                .orElseThrow(() -> new IllegalStateException("System role missing: " + roleName));
        AdminUser user = new AdminUser(tenantId, username, email,
                passwordEncoder.encode(DEFAULT_PASSWORD), fullName);
        user.setRoles(Set.of(role));
        userRepository.save(user);
        log.info("Seeded dev user '{}' with role {}", username, roleName);
    }
}
