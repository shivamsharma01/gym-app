ALTER TABLE notification_template
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE outbound_notification
    ADD COLUMN delivered_at DATETIME NULL;