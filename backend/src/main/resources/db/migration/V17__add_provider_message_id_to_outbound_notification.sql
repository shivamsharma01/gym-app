ALTER TABLE outbound_notification
    ADD INDEX idx_outbound_notification_membership_id (membership_id);
