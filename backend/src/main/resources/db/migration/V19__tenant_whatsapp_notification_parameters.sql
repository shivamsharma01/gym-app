-- ============================================================
-- Tenant WhatsApp configuration
-- ============================================================

CREATE TABLE tenant_whatsapp_configuration (
    id BIGINT NOT NULL AUTO_INCREMENT,

    tenant_id BIGINT NOT NULL,

    public_id VARCHAR(36) NOT NULL,

    business_account_id VARCHAR(100),
    phone_number_id VARCHAR(100) NOT NULL,

    access_token TEXT NOT NULL,

    api_version VARCHAR(30) NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT uk_tenant_whatsapp_configuration_public_id
        UNIQUE (public_id),

    CONSTRAINT uk_tenant_whatsapp_configuration_tenant
        UNIQUE (tenant_id),

    CONSTRAINT fk_tenant_whatsapp_configuration_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)
);


-- ============================================================
-- WhatsApp parameters stored for each outbound notification
-- ============================================================

CREATE TABLE outbound_notification_parameter (
    id BIGINT NOT NULL AUTO_INCREMENT,

    notification_id BIGINT NOT NULL,

    parameter_order INT NOT NULL,

    variable_name VARCHAR(100) NOT NULL,

    parameter_value TEXT,

    PRIMARY KEY (id),

    CONSTRAINT uk_outbound_notification_parameter_order
        UNIQUE (
            notification_id,
            parameter_order
        ),

    CONSTRAINT fk_outbound_notification_parameter_notification
        FOREIGN KEY (notification_id)
        REFERENCES outbound_notification(id)
        ON DELETE CASCADE
);


CREATE INDEX idx_outbound_notification_parameter_notification
    ON outbound_notification_parameter(notification_id);

    
ALTER TABLE outbound_notification
	ADD COLUMN scheduled_at TIMESTAMP NULL;