package com.example.gym.config;

import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Optional one-shot platform SUPER_ADMIN for {@code prod}. Runs only when
 * {@code APP_BOOTSTRAP_SUPERADMIN_PASSWORD} is set and no SUPER_ADMIN user exists yet.
 * After the first successful boot, clear the env var so the password is not kept in compose.
 */
@Component
@Profile("prod")
@Order(100)
public class ProdBootstrapSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdBootstrapSeeder.class);

    private final AdminUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String email;
    private final String fullName;
    private final String password;

    public ProdBootstrapSeeder(
            AdminUserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.superadmin.username:superadmin}") String username,
            @Value("${app.bootstrap.superadmin.email:superadmin@platform.local}") String email,
            @Value("${app.bootstrap.superadmin.full-name:Platform Super Admin}") String fullName,
            @Value("${app.bootstrap.superadmin.password:}") String password) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username == null ? "" : username.trim();
        this.email = email == null ? "" : email.trim();
        this.fullName = fullName == null ? "Platform Super Admin" : fullName.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(password)) {
            log.info("Prod bootstrap: skipped (APP_BOOTSTRAP_SUPERADMIN_PASSWORD unset)");
            return;
        }
        if (password.length() < 10) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_SUPERADMIN_PASSWORD must be at least 10 characters when set");
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(email)) {
            throw new IllegalStateException("Bootstrap username/email must be non-blank when password is set");
        }
        if (userRepository.existsWithRoleName("SUPER_ADMIN")) {
            log.info("Prod bootstrap: SUPER_ADMIN already exists — skipped");
            return;
        }
        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            throw new IllegalStateException(
                    "Bootstrap user username/email already exists without SUPER_ADMIN role — fix manually");
        }

        Role role = roleRepository.findByNameAndTenantIdIsNull("SUPER_ADMIN")
                .orElseThrow(() -> new IllegalStateException("System role missing: SUPER_ADMIN"));
        AdminUser user = new AdminUser(null, username, email, passwordEncoder.encode(password), fullName);
        user.setRoles(Set.of(role));
        userRepository.save(user);
        log.warn("Prod bootstrap: created SUPER_ADMIN '{}' — clear APP_BOOTSTRAP_SUPERADMIN_PASSWORD from env now",
                username);
    }
}
