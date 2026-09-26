package com.example.gym.auth;

import com.example.gym.security.SecurityProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Builds httpOnly refresh-token cookies for same-site SPA auth. */
@Component
public class AuthRefreshCookie {

    public static final String COOKIE_NAME = "gym_refresh";
    public static final String COOKIE_PATH = "/api/v1/auth";

    private final SecurityProperties securityProperties;

    public AuthRefreshCookie(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public ResponseCookie create(String rawRefreshToken) {
        Duration ttl = securityProperties.getJwt().getRefreshTokenTtl();
        return base(rawRefreshToken)
                .maxAge(ttl)
                .build();
    }

    public ResponseCookie clear() {
        return base("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(securityProperties.getJwt().isRefreshCookieSecure())
                .path(COOKIE_PATH)
                .sameSite("Lax");
    }
}
