ALTER TABLE tenant_whatsapp_configuration
    ADD COLUMN created_by VARCHAR(100) NULL AFTER updated_at,
    ADD COLUMN updated_by VARCHAR(100) NULL AFTER created_by,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER updated_by;
