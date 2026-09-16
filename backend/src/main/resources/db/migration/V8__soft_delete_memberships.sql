ALTER TABLE membership
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_membership_deleted
    ON membership (deleted);