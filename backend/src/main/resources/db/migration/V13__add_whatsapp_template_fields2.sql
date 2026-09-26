ALTER TABLE outbound_notification
    ADD COLUMN whatsapp_template_name VARCHAR(100);

ALTER TABLE outbound_notification
    ADD COLUMN whatsapp_language VARCHAR(20);
