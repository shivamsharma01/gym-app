-- Seed the permission catalogue and the built-in system roles with their permission mappings.
-- Idempotency is provided by unique constraints; this runs exactly once (Flyway versioned).

-- Permissions -------------------------------------------------------------------------------------
INSERT INTO permission (public_id, name, description, created_at, updated_at, created_by, updated_by, version)
VALUES
    (UUID(), 'MEMBER_VIEW',           'View members',                       NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBER_CREATE',         'Create members',                     NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBER_UPDATE',         'Update members',                     NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBER_DELETE',         'Delete members',                     NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBERSHIP_VIEW',       'View memberships',                   NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBERSHIP_CREATE',     'Create memberships',                 NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBERSHIP_UPDATE',     'Update memberships',                 NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBERSHIP_FREEZE',     'Freeze/unfreeze memberships',        NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'MEMBERSHIP_CANCEL',     'Cancel memberships',                 NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'PAYMENT_VIEW',          'View payments',                      NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'PAYMENT_CREATE',        'Record payments',                    NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'ATTENDANCE_VIEW',       'View attendance',                    NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'REPORT_VIEW',           'View reports',                       NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'NOTIFICATION_SEND',     'Send notifications',                 NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'DEVICE_VIEW',           'View devices',                       NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'DEVICE_MANAGE',         'Manage devices',                     NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'DEVICE_SYNC',           'Synchronise devices',                NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'DEVICE_REMOTE_CONTROL', 'Remote device control (doors etc.)', NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'SECURITY_ALERT_VIEW',   'View security alerts',               NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'USER_MANAGE',           'Manage admin users',                 NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'ROLE_MANAGE',           'Manage roles',                       NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'AUDIT_VIEW',            'View audit log',                     NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'SETTINGS_MANAGE',       'Manage settings',                    NOW(6), NOW(6), 'system', 'system', 0);

-- System roles ------------------------------------------------------------------------------------
INSERT INTO role (public_id, name, description, tenant_id, is_system, created_at, updated_at, created_by, updated_by, version)
VALUES
    (UUID(), 'SUPER_ADMIN',   'Platform administrator (all tenants)', NULL, TRUE, NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'GYM_OWNER',     'Owner of a gym tenant',                NULL, TRUE, NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'GYM_ADMIN',     'Administrator within a gym',           NULL, TRUE, NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'STAFF',         'Operational staff',                    NULL, TRUE, NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'FRONT_DESK',    'Front-desk reception',                 NULL, TRUE, NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'REPORT_VIEWER', 'Read-only reporting access',           NULL, TRUE, NOW(6), NOW(6), 'system', 'system', 0);

-- Role → permission mappings ----------------------------------------------------------------------

-- SUPER_ADMIN and GYM_OWNER get every permission.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.name IN ('SUPER_ADMIN', 'GYM_OWNER');

-- GYM_ADMIN: full operational control except managing roles.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
WHERE r.name = 'GYM_ADMIN'
  AND p.name IN (
    'MEMBER_VIEW','MEMBER_CREATE','MEMBER_UPDATE','MEMBER_DELETE',
    'MEMBERSHIP_VIEW','MEMBERSHIP_CREATE','MEMBERSHIP_UPDATE','MEMBERSHIP_FREEZE','MEMBERSHIP_CANCEL',
    'PAYMENT_VIEW','PAYMENT_CREATE',
    'ATTENDANCE_VIEW','REPORT_VIEW','NOTIFICATION_SEND',
    'DEVICE_VIEW','DEVICE_MANAGE','DEVICE_SYNC','DEVICE_REMOTE_CONTROL','SECURITY_ALERT_VIEW',
    'USER_MANAGE','AUDIT_VIEW','SETTINGS_MANAGE');

-- STAFF: day-to-day member/membership/payment handling.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
WHERE r.name = 'STAFF'
  AND p.name IN (
    'MEMBER_VIEW','MEMBER_CREATE','MEMBER_UPDATE',
    'MEMBERSHIP_VIEW','MEMBERSHIP_CREATE','MEMBERSHIP_UPDATE',
    'PAYMENT_VIEW','PAYMENT_CREATE',
    'ATTENDANCE_VIEW','DEVICE_VIEW','NOTIFICATION_SEND');

-- FRONT_DESK: reception check-in and payments.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
WHERE r.name = 'FRONT_DESK'
  AND p.name IN (
    'MEMBER_VIEW','MEMBERSHIP_VIEW','PAYMENT_VIEW','PAYMENT_CREATE','ATTENDANCE_VIEW');

-- REPORT_VIEWER: read-only.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
WHERE r.name = 'REPORT_VIEWER'
  AND p.name IN (
    'REPORT_VIEW','ATTENDANCE_VIEW','MEMBER_VIEW','MEMBERSHIP_VIEW','PAYMENT_VIEW');
