-- Checksum of the reader's user list as last compared in full; a matching checksum skips the comparison
ALTER TABLE device
    ADD COLUMN roster_digest VARCHAR(80) NULL,
    ADD COLUMN roster_compared_at DATETIME(6) NULL;
