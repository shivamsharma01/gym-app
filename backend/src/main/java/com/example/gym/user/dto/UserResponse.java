package com.example.gym.user.dto;

import com.example.gym.user.AdminUser;
import java.time.Instant;
import java.util.List;

public record UserResponse(
        String id,
        String username,
        String email,
        String fullName,
        String status,
        List<String> roles,
        Instant createdAt) {

    public static UserResponse from(AdminUser user) {
        List<String> roles = user.getRoles().stream().map(r -> r.getName()).sorted().toList();
        return new UserResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus().name(),
                roles,
                user.getCreatedAt());
    }
}
