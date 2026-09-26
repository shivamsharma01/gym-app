package com.example.gym.auth;

import com.example.gym.audit.AuditActions;
import com.example.gym.audit.AuditService;
import com.example.gym.auth.dto.TokenResponse;
import com.example.gym.auth.dto.UserSummary;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.security.AppUserPrincipal;
import com.example.gym.security.JwtService;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import com.example.gym.user.UserStatus;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication flows: password login (with brute-force lockout), refresh-token rotation with
 * reuse detection, and logout. Access tokens are short-lived JWTs; refresh tokens are opaque,
 * server-persisted only as hashes.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AdminUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final LoginAttemptService loginAttemptService;

    public AuthService(AdminUserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       TenantRepository tenantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuditService auditService,
                       LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.loginAttemptService = loginAttemptService;
    }

    @Transactional
    public IssuedTokens login(String usernameOrEmail, String rawPassword) {
        AdminUser user = userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .orElse(null);

        if (user == null) {
            auditService.recordAuth(AuditActions.LOGIN_FAILURE, AuditActions.RESULT_FAILURE,
                    null, usernameOrEmail, null, Map.of("reason", "unknown_user"));
            throw invalidCredentials();
        }

        if (isLocked(user)) {
            auditService.recordAuth(AuditActions.LOGIN_FAILURE, AuditActions.RESULT_FAILURE,
                    user.getId(), user.getUsername(), user.getTenantId(), Map.of("reason", "locked"));
            throw CommonExceptions.unauthorized("Account is temporarily locked. Try again later.");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            auditService.recordAuth(AuditActions.LOGIN_FAILURE, AuditActions.RESULT_FAILURE,
                    user.getId(), user.getUsername(), user.getTenantId(), Map.of("reason", "disabled"));
            throw CommonExceptions.unauthorized("Account is disabled.");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // Persist the attempt/lock in a separate transaction (this one rolls back on throw).
            loginAttemptService.recordFailure(user.getId());
            throw invalidCredentials();
        }

        // Success: reset lockout counters.
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        IssuedTokens issued = issueTokens(user);
        auditService.recordAuth(AuditActions.LOGIN_SUCCESS, AuditActions.RESULT_SUCCESS,
                user.getId(), user.getUsername(), user.getTenantId(), null);
        return issued;
    }

    @Transactional
    public IssuedTokens refresh(String rawRefreshToken) {
        String hash = jwtService.hashRefreshToken(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> CommonExceptions.unauthorized("Invalid refresh token"));

        if (token.isRevoked()) {
            // Reuse of a rotated/revoked token indicates theft: revoke the entire chain.
            refreshTokenRepository.revokeAllForUser(token.getUserId());
            auditService.recordAuth(AuditActions.TOKEN_REUSE_DETECTED, AuditActions.RESULT_FAILURE,
                    token.getUserId(), null, null, Map.of("tokenId", token.getPublicId()));
            throw CommonExceptions.unauthorized("Refresh token is no longer valid");
        }
        if (!token.isActive()) {
            throw CommonExceptions.unauthorized("Refresh token has expired");
        }

        AdminUser user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> CommonExceptions.unauthorized("Invalid refresh token"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw CommonExceptions.unauthorized("Account is disabled.");
        }

        // Rotate: issue a new token and revoke the old one, linking the chain.
        IssuedTokens issued = issueTokens(user);
        token.setRevoked(true);
        refreshTokenRepository.save(token);

        auditService.recordAuth(AuditActions.TOKEN_REFRESH, AuditActions.RESULT_SUCCESS,
                user.getId(), user.getUsername(), user.getTenantId(), null);
        return issued;
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = jwtService.hashRefreshToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            auditService.recordAuth(AuditActions.LOGOUT, AuditActions.RESULT_SUCCESS,
                    token.getUserId(), null, null, null);
        });
    }

    private IssuedTokens issueTokens(AdminUser user) {
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        var authorities = principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        String accessToken = jwtService.createAccessToken(
                user.getId(), user.getPublicId(), user.getTenantId(), user.getUsername(), authorities);

        String rawRefresh = jwtService.generateRefreshTokenValue();
        RefreshToken refreshToken = new RefreshToken(
                user.getId(), jwtService.hashRefreshToken(rawRefresh), jwtService.refreshTokenExpiry());
        refreshTokenRepository.save(refreshToken);

        TokenResponse response = TokenResponse.of(accessToken, jwtService.accessTokenTtlSeconds(),
                UserSummary.from(user, tenantPublicId(user.getTenantId())));
        return new IssuedTokens(response, rawRefresh);
    }

    private boolean isLocked(AdminUser user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now());
    }

    private String tenantPublicId(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        return tenantRepository.findById(tenantId).map(Tenant::getPublicId).orElse(null);
    }

    private RuntimeException invalidCredentials() {
        // Generic message to avoid user enumeration.
        return CommonExceptions.unauthorized("Invalid username or password");
    }
}
