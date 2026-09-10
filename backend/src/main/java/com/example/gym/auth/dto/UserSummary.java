package com.example.gym.auth.dto;

import com.example.gym.user.AdminUser;
import java.util.List;

/** Non-sensitive view of an admin user (no password hash, no internal numeric ids). */
public record UserSummary(
        String id,
        String username,
        String email,
        String fullName,
        String tenantId,
        List<String> roles,
        List<String> permissions) {

    public static UserSummary from(AdminUser user, String tenantPublicId) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName())
                .sorted()
                .toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getName())
                .distinct()
                .sorted()
                .toList();
        return new UserSummary(
                user.getPublicId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                tenantPublicId,
                roles,
                permissions);
    }
}
