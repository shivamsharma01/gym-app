package com.example.gym.security.domain;

/**
 * Canonical list of permissions used in {@code @PreAuthorize("hasAuthority('...')")} checks and
 * seeded into the {@code permission} table. Keep in sync with Flyway permission inserts.
 */
public enum PermissionCatalog {

    MEMBER_VIEW("View members"),
    MEMBER_CREATE("Create members"),
    MEMBER_UPDATE("Update members"),
    MEMBER_DELETE("Delete members"),
    MEMBERSHIP_VIEW("View memberships"),
    MEMBERSHIP_CREATE("Create memberships"),
    MEMBERSHIP_UPDATE("Update memberships"),
    MEMBERSHIP_FREEZE("Freeze/unfreeze memberships"),
    MEMBERSHIP_CANCEL("Cancel memberships"),
    MEMBERSHIP_DELETE("Delete memberships"),
    PAYMENT_VIEW("View payments"),
    PAYMENT_CREATE("Record payments"),
    ATTENDANCE_VIEW("View attendance"),
    REPORT_VIEW("View reports"),
    NOTIFICATION_SEND("Send notifications"),
    DEVICE_VIEW("View devices"),
    DEVICE_MANAGE("Manage devices"),
    DEVICE_SYNC("Synchronise devices"),
    DEVICE_REMOTE_CONTROL("Remote device control (doors, reboot)"),
    SECURITY_ALERT_VIEW("View security alerts"),
    USER_MANAGE("Manage admin users"),
    ROLE_MANAGE("Manage roles"),
    AUDIT_VIEW("View audit log"),
    SETTINGS_MANAGE("Manage settings"),
    ENQUIRY_VIEW("View public enquiries"),
    ENQUIRY_MANAGE("Update public enquiries");

    private final String description;

    PermissionCatalog(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
