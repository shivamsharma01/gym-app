package com.example.gym.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fails fast in {@code prod} if the JWT signing secret is missing, too short, or still the
 * documented local-dev default. Compose and real deploys must set {@code APP_SECURITY_JWT_SECRET}.
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProdSecurityGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdSecurityGuard.class);
    private static final String DEV_DEFAULT =
            "dev-only-insecure-secret-change-me-0123456789-0123456789";
    private static final int MIN_SECRET_CHARS = 32;

    private final String jwtSecret;
    private final boolean simulatorEnabled;

    public ProdSecurityGuard(
            @Value("${app.security.jwt.secret}") String jwtSecret,
            @Value("${app.gateway.simulator-enabled:false}") boolean simulatorEnabled) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
        this.simulatorEnabled = simulatorEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jwtSecret.isBlank() || jwtSecret.equals(DEV_DEFAULT) || jwtSecret.length() < MIN_SECRET_CHARS) {
            throw new IllegalStateException(
                    "prod profile requires APP_SECURITY_JWT_SECRET (>= "
                            + MIN_SECRET_CHARS
                            + " chars, not the local-dev default). "
                            + "Generate with: openssl rand -base64 48");
        }
        if (simulatorEnabled) {
            throw new IllegalStateException(
                    "prod profile forbids APP_GATEWAY_SIMULATOR_ENABLED=true (not real hardware)");
        }
        log.info("Prod security guard OK (JWT secret present, simulator disabled)");
    }
}
