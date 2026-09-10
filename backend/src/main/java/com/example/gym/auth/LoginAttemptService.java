package com.example.gym.auth;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.security.SecurityProperties;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records failed login attempts and applies lockout. Runs in its own transaction so the counter
 * (and any resulting lock) persists even though the caller ({@code AuthService.login}) then throws
 * an authentication exception, which would otherwise roll back the change.
 */
@Service
public class LoginAttemptService {

    private final AdminUserRepository userRepository;
    private final AuditService auditService;
    private final SecurityProperties securityProperties;

    public LoginAttemptService(AdminUserRepository userRepository, AuditService auditService,
                               SecurityProperties securityProperties) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId) {
        AdminUser user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        String action = AuditActions.LOGIN_FAILURE;
        if (attempts >= securityProperties.getLockout().getMaxFailedAttempts()) {
            user.setLockedUntil(Instant.now().plus(securityProperties.getLockout().getLockDuration()));
            action = AuditActions.ACCOUNT_LOCKED;
        }
        userRepository.save(user);

        auditService.recordAuth(action, AuditActions.RESULT_FAILURE,
                user.getId(), user.getUsername(), user.getTenantId(), Map.of("attempts", attempts));
    }
}
