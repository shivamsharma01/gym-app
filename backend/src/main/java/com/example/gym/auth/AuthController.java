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
import com.example.gym.user.UserService;
import com.example.gym.user.dto.ChangeOwnPasswordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
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
    private final UserService userService;
    private final AuthRefreshCookie authRefreshCookie;

    public AuthController(AuthService authService, AdminUserRepository userRepository,
                          TenantRepository tenantRepository, UserService userService,
                          AuthRefreshCookie authRefreshCookie) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.userService = userService;
        this.authRefreshCookie = authRefreshCookie;
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Authenticate with username/email and password")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        IssuedTokens issued = authService.login(request.usernameOrEmail(), request.password());
        return withRefreshCookie(issued);
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair (rotating)")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = AuthRefreshCookie.COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest request) {
        String raw = resolveRefreshToken(cookieToken, request);
        IssuedTokens issued = authService.refresh(raw);
        return withRefreshCookie(issued);
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "Revoke a refresh token")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthRefreshCookie.COOKIE_NAME, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest request) {
        String raw = firstNonBlank(cookieToken, request == null ? null : request.refreshToken());
        if (StringUtils.hasText(raw)) {
            authService.logout(raw);
        }
        ResponseCookie clear = authRefreshCookie.clear();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .build();
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

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change the current user's password (requires the current password)")
    public void changeOwnPassword(@Valid @RequestBody ChangeOwnPasswordRequest request) {
        userService.changeOwnPassword(
                SecurityUtils.currentUserId(), request.currentPassword(), request.newPassword());
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(IssuedTokens issued) {
        ResponseCookie cookie = authRefreshCookie.create(issued.rawRefreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(issued.response());
    }

    private static String resolveRefreshToken(String cookieToken, RefreshRequest request) {
        String raw = firstNonBlank(cookieToken, request == null ? null : request.refreshToken());
        if (!StringUtils.hasText(raw)) {
            throw CommonExceptions.unauthorized("Refresh token is required");
        }
        return raw;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a;
        }
        if (StringUtils.hasText(b)) {
            return b;
        }
        return null;
    }
}
