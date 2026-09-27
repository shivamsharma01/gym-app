ALTER TABLE outbound_notification
    ADD COLUMN announcement_id BIGINT NULL;

CREATE INDEX idx_outbound_notification_announcement_id
    ON outbound_notification (announcement_id);
    