-- Serial is a staff label, not a reader identity key. Admins may reuse any value.
ALTER TABLE `member` DROP INDEX `uq_member_tenant_serial`;
CREATE INDEX `idx_member_tenant_serial` ON `member` (`tenant_id`, `serial_number`);
