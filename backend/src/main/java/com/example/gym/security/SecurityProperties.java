package com.example.gym.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly-typed security configuration (bound from {@code app.security.*}). */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private final Jwt jwt = new Jwt();
    private final Lockout lockout = new Lockout();

    public Jwt getJwt() {
        return jwt;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public static class Jwt {
        /** HMAC signing secret; MUST be overridden outside dev and be >= 32 bytes. */
        private String secret;
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(30);
        private String issuer = "gym-backend";
        /** When true, refresh cookie is Secure (HTTPS). Disable for local HTTP. */
        private boolean refreshCookieSecure = false;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public boolean isRefreshCookieSecure() {
            return refreshCookieSecure;
        }

        public void setRefreshCookieSecure(boolean refreshCookieSecure) {
            this.refreshCookieSecure = refreshCookieSecure;
        }
    }

    public static class Lockout {
        private int maxFailedAttempts = 5;
        private Duration lockDuration = Duration.ofMinutes(15);

        public int getMaxFailedAttempts() {
            return maxFailedAttempts;
        }

        public void setMaxFailedAttempts(int maxFailedAttempts) {
            this.maxFailedAttempts = maxFailedAttempts;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }
    }
}
