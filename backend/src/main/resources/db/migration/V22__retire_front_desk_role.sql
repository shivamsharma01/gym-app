-- Front desk is removed. Accounts that only had that role become staff, which already covers
-- check-in, members, memberships, and payments.

INSERT INTO user_role (user_id, role_id)
SELECT ur.user_id, staff.id
FROM user_role ur
         JOIN role desk ON desk.id = ur.role_id AND desk.name = 'FRONT_DESK'
         JOIN role staff ON staff.name = 'STAFF' AND staff.tenant_id IS NULL
         LEFT JOIN user_role existing
                   ON existing.user_id = ur.user_id AND existing.role_id = staff.id
WHERE existing.user_id IS NULL;

DELETE
FROM role
WHERE name = 'FRONT_DESK'
  AND tenant_id IS NULL;
