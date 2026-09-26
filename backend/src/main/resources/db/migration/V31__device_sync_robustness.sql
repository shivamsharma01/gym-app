-- Device sync robustness: messageId dedupe, reconciliation conflicts, deny reason, cursor flag.

CREATE TABLE gateway_message_dedupe (
    message_id   CHAR(36)    NOT NULL,
    gateway_id   BIGINT,
    received_at  DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_gmd_expires (expires_at),
    CONSTRAINT fk_gmd_gateway FOREIGN KEY (gateway_id) REFERENCES gateway (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reconciliation_conflict (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    public_id      CHAR(36)     NOT NULL,
    tenant_id      BIGINT       NOT NULL,
    device_id      BIGINT       NOT NULL,
    device_user_id VARCHAR(64)  NOT NULL,
    conflict_type  VARCHAR(32)  NOT NULL,
    details        TEXT,
    status         VARCHAR(16)  NOT NULL,
    resolved_at    DATETIME(6),
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    version        BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_rc_public_id (public_id),
    KEY idx_rc_tenant_device (tenant_id, device_id, status),
    CONSTRAINT fk_rc_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_rc_device FOREIGN KEY (device_id) REFERENCES device (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE attendance_event
    ADD COLUMN deny_reason VARCHAR(64) NULL AFTER fingerprint;

ALTER TABLE attendance_sync_cursor
    ADD COLUMN reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE AFTER last_event_at;
