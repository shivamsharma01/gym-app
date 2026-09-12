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

    // Phase 2: business domain
    public static final String PLAN_CREATED = "PLAN_CREATED";
    public static final String PLAN_UPDATED = "PLAN_UPDATED";
    public static final String PLAN_ARCHIVED = "PLAN_ARCHIVED";
    public static final String MEMBER_CREATED = "MEMBER_CREATED";
    public static final String MEMBER_UPDATED = "MEMBER_UPDATED";
    public static final String MEMBER_DELETED = "MEMBER_DELETED";
    public static final String MEMBERSHIP_CREATED = "MEMBERSHIP_CREATED";
    public static final String MEMBERSHIP_RENEWED = "MEMBERSHIP_RENEWED";
    public static final String MEMBERSHIP_FROZEN = "MEMBERSHIP_FROZEN";
    public static final String MEMBERSHIP_UNFROZEN = "MEMBERSHIP_UNFROZEN";
    public static final String MEMBERSHIP_CANCELLED = "MEMBERSHIP_CANCELLED";
    public static final String PAYMENT_RECORDED = "PAYMENT_RECORDED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    // Phase 3: device integration
    public static final String GATEWAY_REGISTERED = "GATEWAY_REGISTERED";
    public static final String DEVICE_CREATED = "DEVICE_CREATED";
    public static final String DEVICE_UPDATED = "DEVICE_UPDATED";
    public static final String DEVICE_MAPPING_CREATED = "DEVICE_MAPPING_CREATED";
    public static final String DEVICE_SYNC_ENQUEUED = "DEVICE_SYNC_ENQUEUED";
    public static final String DEVICE_SYNC_RETRIED = "DEVICE_SYNC_RETRIED";
    public static final String DEVICE_SYNC_CANCELLED = "DEVICE_SYNC_CANCELLED";
    public static final String DEVICE_RECONCILE_REQUESTED = "DEVICE_RECONCILE_REQUESTED";
    public static final String DEVICE_REMOTE_DOOR = "DEVICE_REMOTE_DOOR";

    public static final String ENQUIRY_RECEIVED = "ENQUIRY_RECEIVED";
    public static final String ENQUIRY_UPDATED = "ENQUIRY_UPDATED";
    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";
    public static final String NOTIFICATION_QUEUED = "NOTIFICATION_QUEUED";
    public static final String ANNOUNCEMENT_CREATED = "ANNOUNCEMENT_CREATED";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";
}
