-- Device roster import: member provenance, inferred membership end dates, cached device users.

ALTER TABLE member
    ADD COLUMN creation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER notes;

ALTER TABLE membership
    ADD COLUMN end_date_inferred TINYINT(1) NOT NULL DEFAULT 0 AFTER end_date;

CREATE TABLE device_user_snapshot (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    public_id       CHAR(36)     NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    device_id       BIGINT       NOT NULL,
    device_user_id  VARCHAR(64)  NOT NULL,
    name            VARCHAR(120),
    frozen          TINYINT(1)   NOT NULL DEFAULT 0,
    valid_from      DATE,
    valid_to        DATE,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_device_user_snapshot_public_id (public_id),
    UNIQUE KEY uq_device_user_snapshot_device_user (device_id, device_user_id),
    KEY idx_device_user_snapshot_tenant (tenant_id),
    KEY idx_device_user_snapshot_device (device_id),
    CONSTRAINT fk_device_user_snapshot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_device_user_snapshot_device FOREIGN KEY (device_id) REFERENCES device (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
