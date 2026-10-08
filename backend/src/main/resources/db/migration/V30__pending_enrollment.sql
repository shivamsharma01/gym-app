-- V8: a reader id the server did not allocate. No member row is created from it.

CREATE TABLE device_observed_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    public_id       CHAR(36)     NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    device_id       BIGINT       NOT NULL,
    device_user_id  VARCHAR(64)  NOT NULL,
    reader_name     VARCHAR(127),
    reader_name_ex  VARCHAR(127),
    user_status     INT          NOT NULL,
    valid_from      VARCHAR(40),
    valid_to        VARCHAR(40),
    authority       VARCHAR(32),
    face_sha256     VARCHAR(64),
    observed_at     DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_observed_user_public (public_id),
    UNIQUE KEY uq_observed_user_device (device_id, device_user_id),
    CONSTRAINT fk_observed_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_observed_user_device FOREIGN KEY (device_id) REFERENCES device (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE pending_enrollment (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    public_id       CHAR(36)     NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    device_id       BIGINT       NOT NULL,
    device_user_id  VARCHAR(64)  NOT NULL,
    review_status   VARCHAR(16)  NOT NULL,
    observed_at     DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pending_enrollment_public (public_id),
    UNIQUE KEY uq_pending_enrollment_device (device_id, device_user_id),
    CONSTRAINT fk_pending_enrollment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_pending_enrollment_device FOREIGN KEY (device_id) REFERENCES device (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
