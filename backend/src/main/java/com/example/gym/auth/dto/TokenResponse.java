package com.example.gym.auth.dto;

/** Access token response; refresh is delivered only via httpOnly cookie. */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserSummary user) {

    public static TokenResponse of(String accessToken, long expiresInSeconds, UserSummary user) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
