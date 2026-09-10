package com.example.gym.audit;

/** Stable audit action identifiers. */
public final class AuditActions {

    private AuditActions() {
    }

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String TOKEN_REFRESH = "TOKEN_REFRESH";
    public static final String TOKEN_REUSE_DETECTED = "TOKEN_REUSE_DETECTED";
    public static final String LOGOUT = "LOGOUT";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_ROLES_CHANGED = "USER_ROLES_CHANGED";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";
}
