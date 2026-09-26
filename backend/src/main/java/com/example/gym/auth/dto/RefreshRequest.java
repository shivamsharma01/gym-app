package com.example.gym.auth.dto;

/** Optional body refresh token; prefer httpOnly cookie when present. */
public record RefreshRequest(String refreshToken) {
}
