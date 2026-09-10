package com.example.gym.security.web;

import com.example.gym.security.domain.Permission;

public record PermissionResponse(String name, String description) {

    public static PermissionResponse from(Permission permission) {
        return new PermissionResponse(permission.getName(), permission.getDescription());
    }
}
