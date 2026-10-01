-- Add device_authority column to member table with default 'USER'
ALTER TABLE member
    ADD COLUMN device_authority VARCHAR(32) NOT NULL DEFAULT 'USER';
