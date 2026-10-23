-- Member serial was a staff label, not a reader id. Reader ids live on each mapping.
ALTER TABLE `member` DROP INDEX `idx_member_tenant_serial`;
ALTER TABLE `member` DROP COLUMN `serial_number`;
