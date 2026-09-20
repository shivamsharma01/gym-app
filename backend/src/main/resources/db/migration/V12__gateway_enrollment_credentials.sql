-- One-time enrollment tokens + rotatable long-lived gateway credentials.
-- Existing gateways keep their current token_hash as the operational credential.

ALTER TABLE gateway
    ADD COLUMN enrollment_token_hash CHAR(64) NULL AFTER token_hash,
    ADD COLUMN enrollment_expires_at DATETIME(6) NULL AFTER enrollment_token_hash,
    ADD COLUMN enrollment_consumed_at DATETIME(6) NULL AFTER enrollment_expires_at,
    ADD COLUMN token_expires_at DATETIME(6) NULL AFTER enrollment_consumed_at,
    ADD COLUMN next_token_hash CHAR(64) NULL AFTER token_expires_at;

-- Legacy rows: already "enrolled"; keep operational hash; give them a 90-day expiry window.
UPDATE gateway
SET enrollment_consumed_at = created_at,
    token_expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 90 DAY)
WHERE enrollment_consumed_at IS NULL;

CREATE UNIQUE INDEX uq_gateway_enrollment_token_hash ON gateway (enrollment_token_hash);
CREATE UNIQUE INDEX uq_gateway_next_token_hash ON gateway (next_token_hash);
