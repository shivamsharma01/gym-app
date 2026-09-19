package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.GatewayRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-gateway authentication: operational credentials (and pending rotated credentials) are stored
 * only as SHA-256 hashes. An optional deployment-wide shared token can also be accepted for the
 * local simulator. Enrollment tokens are never accepted here — use {@link GatewayCredentialService}.
 * Tokens are never logged.
 */
@Service
public class GatewayAuthService {

    public enum Kind {
        /** Identified a specific gateway via its operational (or just-promoted) credential. */
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

    /**
     * Authenticates a presented operational credential. Matching {@code next_token_hash} promotes
     * that hash to current (rotation confirm) within this transaction.
     */
    @Transactional
    public Optional<Outcome> authenticate(String presented) {
        if (StringUtils.hasText(presented)) {
            String hashed = hash(presented);
            Optional<Gateway> byHash = gatewayRepository.findByTokenHash(hashed);
            if (byHash.isPresent()) {
                return Optional.of(new Outcome(Kind.GATEWAY, byHash.get()));
            }
            Optional<Gateway> byNext = gatewayRepository.findByNextTokenHash(hashed);
            if (byNext.isPresent()) {
                Gateway gateway = byNext.get();
                gateway.setTokenHash(hashed);
                gateway.setNextTokenHash(null);
                return Optional.of(new Outcome(Kind.GATEWAY, gatewayRepository.save(gateway)));
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
