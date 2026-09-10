package com.example.gym.auth;

import com.example.gym.auth.dto.LoginRequest;
import com.example.gym.auth.dto.RefreshRequest;
import com.example.gym.auth.dto.TokenResponse;
import com.example.gym.auth.dto.UserSummary;
import com.example.gym.common.error.CommonExceptions;
import com.example.gym.security.SecurityUtils;
import com.example.gym.tenant.Tenant;
import com.example.gym.tenant.TenantRepository;
import com.example.gym.user.AdminUser;
import com.example.gym.user.AdminUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final AdminUserRepository userRepository;
    private final TenantRepository tenantRepository;

    public AuthController(AuthService authService, AdminUserRepository userRepository,
                          TenantRepository tenantRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Authenticate with username/email and password")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.usernameOrEmail(), request.password());
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair (rotating)")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    @Operation(summary = "Return the current authenticated user, roles and permissions")
    public UserSummary me() {
        Long userId = SecurityUtils.currentUserId();
        AdminUser user = userRepository.findById(userId)
                .orElseThrow(() -> CommonExceptions.unauthorized("No authenticated user"));
        String tenantPublicId = user.getTenantId() == null ? null
                : tenantRepository.findById(user.getTenantId()).map(Tenant::getPublicId).orElse(null);
        return UserSummary.from(user, tenantPublicId);
    }
}
