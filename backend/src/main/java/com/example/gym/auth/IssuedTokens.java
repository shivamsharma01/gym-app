package com.example.gym.auth;

import com.example.gym.auth.dto.TokenResponse;
import com.example.gym.auth.dto.UserSummary;

/** Internal login/refresh result including the raw refresh token for Set-Cookie. */
public record IssuedTokens(TokenResponse response, String rawRefreshToken) {
}
