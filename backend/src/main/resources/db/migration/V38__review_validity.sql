ALTER TABLE device_review_item
    ADD COLUMN baseline_valid_from VARCHAR(40) NULL,
    ADD COLUMN baseline_valid_to VARCHAR(40) NULL,
    ADD COLUMN server_valid_from VARCHAR(40) NULL,
    ADD COLUMN server_valid_to VARCHAR(40) NULL,
    ADD COLUMN reader_valid_from VARCHAR(40) NULL,
    ADD COLUMN reader_valid_to VARCHAR(40) NULL;

ALTER TABLE device_review_snapshot
    ADD COLUMN reader_valid_from VARCHAR(40) NULL,
    ADD COLUMN reader_valid_to VARCHAR(40) NULL;
