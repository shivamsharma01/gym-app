package com.example.gym.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserSummary user) {

    public static TokenResponse of(String accessToken, String refreshToken, long expiresInSeconds,
                                   UserSummary user) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user);
    }
}
