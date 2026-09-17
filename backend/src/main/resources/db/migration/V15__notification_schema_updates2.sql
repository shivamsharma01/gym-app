ALTER TABLE notification_template
    ADD COLUMN whatsapp_template_name VARCHAR(100);

ALTER TABLE notification_template
    ADD COLUMN whatsapp_language VARCHAR(20);
