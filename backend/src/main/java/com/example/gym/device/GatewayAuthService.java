package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.GatewayRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Per-gateway authentication (Phase 3 decision): each gateway is issued a random token at create
 * time. Only the SHA-256 hash is stored. An optional deployment-wide shared token can also be
 * accepted. Tokens are never logged.
 */
@Service
public class GatewayAuthService {

    public enum Kind {
        /** Identified a specific gateway via its per-gateway token. */
        GATEWAY,
        /** Deployment shared token (or anonymous-dev) — gateway id is bound on REGISTER. */
        SHARED
    }

    public record Outcome(Kind kind, Gateway gateway) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GatewayRepository gatewayRepository;
    private final GatewayProperties properties;

    public GatewayAuthService(GatewayRepository gatewayRepository, GatewayProperties properties) {
        this.gatewayRepository = gatewayRepository;
        this.properties = properties;
    }

    public String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public Optional<Outcome> authenticate(String presented) {
        if (StringUtils.hasText(presented)) {
            Optional<Gateway> byHash = gatewayRepository.findByTokenHash(hash(presented));
            if (byHash.isPresent()) {
                return Optional.of(new Outcome(Kind.GATEWAY, byHash.get()));
            }
            String shared = properties.getSharedToken();
            if (StringUtils.hasText(shared) && constantTimeEquals(shared, presented)) {
                return Optional.of(new Outcome(Kind.SHARED, null));
            }
            return Optional.empty();
        }
        // No token presented: allowed only when no shared token is configured (local dev).
        if (!StringUtils.hasText(properties.getSharedToken())) {
            return Optional.of(new Outcome(Kind.SHARED, null));
        }
        return Optional.empty();
    }

    private boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
