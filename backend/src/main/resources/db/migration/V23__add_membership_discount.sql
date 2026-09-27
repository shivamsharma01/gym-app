ALTER TABLE membership
    ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN discount_approved_by_user_id BIGINT NULL,
    ADD COLUMN discount_approved_by_username VARCHAR(100) NULL,
    ADD COLUMN discount_approved_at DATETIME(6) NULL;