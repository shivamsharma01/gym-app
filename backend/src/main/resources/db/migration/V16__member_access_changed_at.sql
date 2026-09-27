-- When the server last changed a member's access (membership dates, freeze, activation).
-- Compared with device change times so the latest change wins when both sides edited offline.
ALTER TABLE member
    ADD COLUMN access_changed_at DATETIME(6) NULL AFTER profile_changed_at;

UPDATE member SET access_changed_at = profile_changed_at WHERE access_changed_at IS NULL;
