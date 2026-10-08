-- V10: one conflict holds a separate snapshot for each reader. Neither snapshot replaces the other.

CREATE TABLE device_review_snapshot (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    public_id        CHAR(36)     NOT NULL,
    tenant_id        BIGINT       NOT NULL,
    review_item_id   BIGINT       NOT NULL,
    device_id        BIGINT       NOT NULL,
    device_user_id   VARCHAR(64)  NOT NULL,
    reader_name      VARCHAR(127),
    reader_name_ex   VARCHAR(127),
    reader_authority VARCHAR(32),
    observed_at      DATETIME(6)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    version          BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_review_snapshot_public (public_id),
    UNIQUE KEY uq_review_snapshot_reader (review_item_id, device_id),
    CONSTRAINT fk_review_snapshot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_review_snapshot_item FOREIGN KEY (review_item_id) REFERENCES device_review_item (id),
    CONSTRAINT fk_review_snapshot_device FOREIGN KEY (device_id) REFERENCES device (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
