INSERT INTO permission (
    public_id,
    name,
    description,
    created_at,
    updated_at,
    created_by,
    updated_by,
    version
)
SELECT
    UUID(),
    'MEMBERSHIP_DELETE',
    'Delete memberships',
    NOW(6),
    NOW(6),
    'system',
    'system',
    0
    WHERE NOT EXISTS (
    SELECT 1
    FROM permission
    WHERE name = 'MEMBERSHIP_DELETE'
);

-- Grant the permission to GYM_OWNER and SUPER_ADMIN
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r
         JOIN permission p ON p.name = 'MEMBERSHIP_DELETE'
WHERE r.name IN ('SUPER_ADMIN', 'GYM_OWNER')
  AND NOT EXISTS (
    SELECT 1
    FROM role_permission rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
);