package com.example.gym.user;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.auth.RefreshTokenRepository;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.security.SecurityUtils;
import com.example.gym.security.domain.Role;
import com.example.gym.security.domain.RoleRepository;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantGuard;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.user.dto.CreateUserRequest;
import com.example.gym.user.dto.UpdateUserRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-user management. All reads/writes are tenant-scoped: a tenant caller can only ever see or
 * modify users within their own tenant; platform SUPER_ADMINs (no tenant) may operate across tenants.
 */
@Service
public class UserService {

    private final AdminUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(AdminUserRepository userRepository, RoleRepository roleRepository,
                       TenantRepository tenantRepository, RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<AdminUser> list(Long currentTenantId, Pageable pageable) {
        if (currentTenantId == null) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByTenantId(currentTenantId, pageable);
    }

    @Transactional(readOnly = true)
    public AdminUser getByPublicId(String publicId, Long currentTenantId) {
        AdminUser user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> CommonExceptions.notFound("User"));
        TenantGuard.check(user.getTenantId(), currentTenantId, "User");
        return user;
    }

    @Transactional
    public AdminUser create(CreateUserRequest request, Long currentTenantId) {
        Long targetTenantId = resolveTargetTenant(request.tenantId(), currentTenantId);

        if (userRepository.existsByUsername(request.username())) {
            throw CommonExceptions.conflict("Username already exists");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw CommonExceptions.conflict("Email already exists");
        }

        Set<Role> roles = resolveRoles(request.roles(), targetTenantId);

        AdminUser user = new AdminUser(
                targetTenantId,
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName());
        user.setRoles(roles);
        AdminUser saved = userRepository.save(user);

        auditService.record(AuditActions.USER_CREATED, AuditActions.RESULT_SUCCESS,
                "AdminUser", saved.getPublicId(),
                Map.of("username", saved.getUsername(), "roles", request.roles()));
        return saved;
    }

    @Transactional
    public AdminUser update(String publicId, UpdateUserRequest request, Long currentTenantId) {
        AdminUser user = getByPublicId(publicId, currentTenantId);
        if (!user.getEmail().equals(request.email()) && userRepository.existsByEmail(request.email())) {
            throw CommonExceptions.conflict("Email already exists");
        }
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        AdminUser saved = userRepository.save(user);
        auditService.record(AuditActions.USER_UPDATED, AuditActions.RESULT_SUCCESS,
                "AdminUser", saved.getPublicId(), null);
        return saved;
    }

    @Transactional
    public AdminUser assignRoles(String publicId, List<String> roleNames, Long currentTenantId) {
        AdminUser user = getByPublicId(publicId, currentTenantId);
        Set<Role> roles = resolveRoles(roleNames, user.getTenantId());
        user.setRoles(roles);
        AdminUser saved = userRepository.save(user);
        auditService.record(AuditActions.USER_ROLES_CHANGED, AuditActions.RESULT_SUCCESS,
                "AdminUser", saved.getPublicId(), Map.of("roles", roleNames));
        return saved;
    }

    @Transactional
    public void disable(String publicId, Long currentTenantId) {
        AdminUser user = getByPublicId(publicId, currentTenantId);
        if (user.getId().equals(SecurityUtils.currentUserId())) {
            throw CommonExceptions.badRequest("You cannot disable your own account");
        }
        user.setStatus(UserStatus.DISABLED);
        userRepository.save(user);
        // Revoke active sessions immediately.
        refreshTokenRepository.revokeAllForUser(user.getId());
        auditService.record(AuditActions.USER_DISABLED, AuditActions.RESULT_SUCCESS,
                "AdminUser", user.getPublicId(), null);
    }

    @Transactional
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        AdminUser user = userRepository.findById(userId)
                .orElseThrow(() -> CommonExceptions.unauthorized("No authenticated user"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw CommonExceptions.badRequest("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw CommonExceptions.badRequest("New password must be different from the current password");
        }
        applyPassword(user, newPassword);
        auditService.record(AuditActions.USER_PASSWORD_CHANGED, AuditActions.RESULT_SUCCESS,
                "AdminUser", user.getPublicId(), null);
    }

    /**
     * Sets a gym staff password without knowing the old one. Platform callers may target any tenant
     * user; gym callers may only target users in their own tenant. Platform SUPER_ADMIN accounts
     * (no tenant) cannot be reset this way.
     */
    @Transactional
    public AdminUser resetPassword(String publicId, String newPassword, Long currentTenantId) {
        AdminUser user = getByPublicId(publicId, currentTenantId);
        if (user.getTenantId() == null) {
            throw CommonExceptions.badRequest("Platform accounts cannot be reset this way");
        }
        if (user.getId().equals(SecurityUtils.currentUserId())) {
            throw CommonExceptions.badRequest("Use your profile to change your own password");
        }
        applyPassword(user, newPassword);
        auditService.record(AuditActions.USER_PASSWORD_SET, AuditActions.RESULT_SUCCESS,
                "AdminUser", user.getPublicId(), Map.of("username", user.getUsername()));
        return user;
    }

    @Transactional(readOnly = true)
    public List<AdminUser> listStaffForTenant(String tenantPublicId) {
        Tenant tenant = tenantRepository.findByPublicId(tenantPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Tenant"));
        return userRepository.findByTenantIdOrderByUsernameAsc(tenant.getId());
    }

    @Transactional(readOnly = true)
    public AdminUser getByTenantAndUsername(String tenantPublicId, String username) {
        Tenant tenant = tenantRepository.findByPublicId(tenantPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Tenant"));
        return userRepository.findByTenantIdAndUsername(tenant.getId(), username)
                .orElseThrow(() -> CommonExceptions.notFound("User"));
    }

    private void applyPassword(AdminUser user, String rawPassword) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    private Long resolveTargetTenant(String requestedTenantPublicId, Long currentTenantId) {
        if (currentTenantId != null) {
            return currentTenantId; // tenant callers always create within their own tenant
        }
        if (requestedTenantPublicId == null || requestedTenantPublicId.isBlank()) {
            throw CommonExceptions.badRequest("tenantId is required for platform-level user creation");
        }
        Tenant tenant = tenantRepository.findByPublicId(requestedTenantPublicId)
                .orElseThrow(() -> CommonExceptions.notFound("Tenant"));
        return tenant.getId();
    }

    private Set<Role> resolveRoles(List<String> roleNames, Long tenantId) {
        Map<String, Role> available = roleRepository.findByTenantIdIsNullOrTenantId(tenantId).stream()
                .collect(Collectors.toMap(Role::getName, r -> r, (a, b) -> a));
        Set<Role> resolved = new LinkedHashSet<>();
        for (String name : roleNames) {
            Role role = available.get(name);
            if (role == null) {
                throw CommonExceptions.badRequest("Unknown role: " + name);
            }
            resolved.add(role);
        }
        return resolved;
    }
}
