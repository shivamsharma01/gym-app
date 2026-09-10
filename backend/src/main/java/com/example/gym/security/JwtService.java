package com.example.gym.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates signed (HMAC-SHA256) stateless access tokens, and generates opaque refresh
 * tokens. Refresh tokens are random secrets; only their SHA-256 hash is persisted.
 */
@Service
public class JwtService {

    private final SecurityProperties properties;
    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String publicId, Long tenantId, String username,
                                    List<String> authorities) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getJwt().getAccessTokenTtl());
        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(username)
                .claim("uid", userId)
                .claim("pid", publicId)
                .claim("tid", tenantId)
                .claim("authorities", authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getJwt().getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long accessTokenTtlSeconds() {
        return properties.getJwt().getAccessTokenTtl().toSeconds();
    }

    /** Generates a new opaque refresh token (raw value returned to the client only). */
    public String generateRefreshTokenValue() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public Instant refreshTokenExpiry() {
        return Instant.now().plus(properties.getJwt().getRefreshTokenTtl());
    }

    /** SHA-256 hash (hex) of a refresh token value; only this is stored server-side. */
    public String hashRefreshToken(String rawValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
