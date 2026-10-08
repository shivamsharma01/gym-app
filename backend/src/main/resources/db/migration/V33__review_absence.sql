-- V11: a mapped id missing from a trusted list is one device-missing review item.

ALTER TABLE device_review_item
    ADD COLUMN reader_absent BOOLEAN NOT NULL DEFAULT FALSE;
