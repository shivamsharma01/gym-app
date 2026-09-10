package com.example.gym.security.domain;

/** Built-in role names. The actual role→permission mapping is seeded in {@code V1__baseline.sql}. */
public enum SystemRole {
    SUPER_ADMIN,
    GYM_OWNER,
    GYM_ADMIN,
    STAFF,
    FRONT_DESK,
    REPORT_VIEWER
}
