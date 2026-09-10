package com.example.gym.security.web;

import com.example.gym.security.domain.Role;
import java.util.List;

public record RoleResponse(
        String id,
        String name,
        String description,
        boolean system,
        List<String> permissions) {

    public static RoleResponse from(Role role) {
        List<String> perms = role.getPermissions().stream()
                .map(p -> p.getName())
                .sorted()
                .toList();
        return new RoleResponse(role.getPublicId(), role.getName(), role.getDescription(),
                role.isSystem(), perms);
    }
}
