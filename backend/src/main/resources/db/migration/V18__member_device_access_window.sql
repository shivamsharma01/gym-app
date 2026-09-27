-- The access window last sent to the member's devices (dates + enabled). The hourly access check
-- compares it with what the memberships say today and only pushes (and stamps access_changed_at)
-- when something the device holds actually changes, e.g. one membership ended and the next begins.
ALTER TABLE member
    ADD COLUMN device_valid_from DATE NULL AFTER face_changed_at,
    ADD COLUMN device_valid_to DATE NULL AFTER device_valid_from,
    ADD COLUMN device_enabled BOOLEAN NULL AFTER device_valid_to;
