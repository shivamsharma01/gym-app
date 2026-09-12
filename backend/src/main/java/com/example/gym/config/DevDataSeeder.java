package com.example.gym.config;

import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
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
 * Dev bootstrap: platform SUPER_ADMIN only. Gyms and gym staff are created by SUPER_ADMIN via
 * the enroll UI (then gym admins create further staff/members). Never runs in test/prod.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String DEFAULT_PASSWORD = "ChangeMe123!";

    private final AdminUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(AdminUserRepository userRepository, RoleRepository roleRepository,
                         PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByUsername("superadmin")) {
            return;
        }
        Role role = roleRepository.findByNameAndTenantIdIsNull("SUPER_ADMIN")
                .orElseThrow(() -> new IllegalStateException("System role missing: SUPER_ADMIN"));
        AdminUser user = new AdminUser(null, "superadmin", "superadmin@platform.local",
                passwordEncoder.encode(DEFAULT_PASSWORD), "Platform Super Admin");
        user.setRoles(Set.of(role));
        userRepository.save(user);
        log.warn("DEV bootstrap: created platform SUPER_ADMIN 'superadmin' with password '{}'. "
                + "Enroll gyms from /app/platform/gyms — no demo gym is seeded.", DEFAULT_PASSWORD);
    }
}
