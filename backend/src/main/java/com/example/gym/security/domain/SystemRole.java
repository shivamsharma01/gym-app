package com.example.gym.security.domain;

/** Built-in role names. The actual role→permission mapping is seeded by Flyway. */
public enum SystemRole {
    SUPER_ADMIN,
    GYM_ADMIN,
    STAFF,
    REPORT_VIEWER
}
