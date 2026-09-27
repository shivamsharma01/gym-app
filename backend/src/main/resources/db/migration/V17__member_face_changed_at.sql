-- When the member's face was last set or removed (a removal leaves no member_face row to hold it).
-- Sent with face commands so gateways can apply "latest change wins".
ALTER TABLE member
    ADD COLUMN face_changed_at DATETIME(6) NULL AFTER access_changed_at;

UPDATE member m
    LEFT JOIN member_face f ON f.member_id = m.id
SET m.face_changed_at = COALESCE(f.changed_at, m.profile_changed_at);
