-- V15: a bootstrap report can be repeated. The run id stays on the review and enrollment rows.

ALTER TABLE device
    ADD COLUMN roster_trusted_empty BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE device_review_item
    ADD COLUMN bootstrap_run_id CHAR(36) NULL;

ALTER TABLE pending_enrollment
    ADD COLUMN bootstrap_run_id CHAR(36) NULL;
