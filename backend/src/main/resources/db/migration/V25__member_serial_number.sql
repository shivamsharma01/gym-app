-- Editable serial number per member: the id the gym uses on its readers (device user id).
-- The member's database id and member_code stay the server identity.
ALTER TABLE member
    ADD COLUMN serial_number VARCHAR(32) NULL,
    ADD UNIQUE KEY uq_member_tenant_serial (tenant_id, serial_number);

-- A reader moving a member to a new serial keeps the old device user id until the new one exists.
ALTER TABLE member_device_mapping
    ADD COLUMN pending_device_user_id VARCHAR(64) NULL;

-- Backfill only when every reader holds the member under the same id and no other member uses it.
-- Members whose readers disagree keep an empty serial until staff choose one.
UPDATE member m
    JOIN (SELECT agreed.member_id, agreed.device_user_id
          FROM (SELECT member_id, tenant_id, MIN(device_user_id) AS device_user_id
                FROM member_device_mapping
                GROUP BY member_id, tenant_id
                HAVING COUNT(DISTINCT device_user_id) = 1) agreed
                   JOIN (SELECT tenant_id, device_user_id
                         FROM member_device_mapping
                         GROUP BY tenant_id, device_user_id
                         HAVING COUNT(DISTINCT member_id) = 1) sole
                        ON sole.tenant_id = agreed.tenant_id
                            AND sole.device_user_id = agreed.device_user_id
          WHERE CHAR_LENGTH(agreed.device_user_id) <= 32) x ON x.member_id = m.id
SET m.serial_number = x.device_user_id;
