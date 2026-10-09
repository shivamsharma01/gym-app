package com.example.gym.device;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.repo.GatewayRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-gateway authentication. Operational credentials (and pending rotated credentials) are stored
 * only as SHA-256 hashes. A connection is accepted only when the presented token matches one
 * gateway and {@code token_expires_at} is still in the future. There is no shared token and no
 * anonymous path. Enrollment tokens are never accepted here — use {@link GatewayService#enroll}.
 * Tokens are never logged.
 */
@Service
public class GatewayAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GatewayRepository gatewayRepository;

    public GatewayAuthService(GatewayRepository gatewayRepository) {
        this.gatewayRepository = gatewayRepository;
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
     * Authenticates a presented operational credential. The gateway id is the row that owns the
     * hash, never a value from the caller. Matching {@code next_token_hash} promotes that hash to
     * current (rotation confirm) only when the credential has not expired. A missing expiry fails
     * closed.
     */
    @Transactional
    public Optional<Gateway> authenticate(String presented) {
        if (!StringUtils.hasText(presented)) {
            return Optional.empty();
        }
        String hashed = hash(presented);
        Optional<Gateway> byHash = gatewayRepository.findByTokenHash(hashed);
        if (byHash.isPresent()) {
            Gateway gateway = byHash.get();
            if (!credentialCurrent(gateway)) {
                return Optional.empty();
            }
            return Optional.of(gateway);
        }
        Optional<Gateway> byNext = gatewayRepository.findByNextTokenHash(hashed);
        if (byNext.isEmpty()) {
            return Optional.empty();
        }
        Gateway gateway = byNext.get();
        if (!credentialCurrent(gateway)) {
            return Optional.empty();
        }
        gateway.setTokenHash(hashed);
        gateway.setNextTokenHash(null);
        return Optional.of(gatewayRepository.save(gateway));
    }

    /**
     * Whether a WebSocket already bound to {@code credentialHash} may stay open. HTTP still accepts
     * the previous operational token until the replacement is used. A socket bound to that previous
     * token is not: issuing a rotation ({@code next_token_hash} set) or passing expiry closes it.
     * A socket bound to the replacement hash stays valid. This does not promote a rotation.
     */
    public boolean sessionAllows(Gateway gateway, String credentialHash) {
        if (gateway == null || !StringUtils.hasText(credentialHash) || !credentialCurrent(gateway)) {
            return false;
        }
        if (StringUtils.hasText(gateway.getNextTokenHash())) {
            return credentialHash.equals(gateway.getNextTokenHash());
        }
        return credentialHash.equals(gateway.getTokenHash());
    }

    /** True only when an expiry is stored and is still strictly in the future. */
    private boolean credentialCurrent(Gateway gateway) {
        Instant expiresAt = gateway.getTokenExpiresAt();
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }
}
