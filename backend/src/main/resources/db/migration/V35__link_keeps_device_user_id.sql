-- A staff link replaces the user already on the reader's id. Other writes still refuse that overwrite.

ALTER TABLE desired_member
    ADD COLUMN keep_device_user_id BOOLEAN NOT NULL DEFAULT FALSE;
