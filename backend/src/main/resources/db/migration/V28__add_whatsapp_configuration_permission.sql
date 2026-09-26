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
    'WHATSAPP_CONFIGURATION_MANAGE',
    'Manage tenant WhatsApp configuration',
    NOW(6),
    NOW(6),
    'system',
    'system',
    0
WHERE NOT EXISTS (
    SELECT 1
    FROM permission
    WHERE name = 'WHATSAPP_CONFIGURATION_MANAGE'
);


INSERT INTO role_permission (role_id, permission_id)
SELECT
    r.id,
    p.id
FROM role r
JOIN permission p
    ON p.name = 'WHATSAPP_CONFIGURATION_MANAGE'
WHERE r.name IN (
    'SUPER_ADMIN',
    'GYM_OWNER',
    'GYM_ADMIN'
)
AND NOT EXISTS (
    SELECT 1
    FROM role_permission rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
);
