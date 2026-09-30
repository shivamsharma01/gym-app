-- Gym admin gets every permission the gym owner role had. Existing owner accounts become gym admins.
-- The GYM_OWNER role is then removed.

INSERT INTO role_permission (role_id, permission_id)
SELECT admin.id, p.id
FROM role admin
         JOIN permission p
         LEFT JOIN role_permission rp
                   ON rp.role_id = admin.id AND rp.permission_id = p.id
WHERE admin.name = 'GYM_ADMIN'
  AND admin.tenant_id IS NULL
  AND rp.role_id IS NULL;

INSERT INTO user_role (user_id, role_id)
SELECT ur.user_id, admin.id
FROM user_role ur
         JOIN role owner ON owner.id = ur.role_id AND owner.name = 'GYM_OWNER'
         JOIN role admin ON admin.name = 'GYM_ADMIN' AND admin.tenant_id IS NULL
         LEFT JOIN user_role existing
                   ON existing.user_id = ur.user_id AND existing.role_id = admin.id
WHERE existing.user_id IS NULL;

DELETE
FROM role
WHERE name = 'GYM_OWNER'
  AND tenant_id IS NULL;
