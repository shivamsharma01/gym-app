-- V1: one flagged reader is written from a desired projection, not from the command outbox.
-- The outbox remains for readers that are not flagged.

ALTER TABLE device
    ADD COLUMN projection_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE reader_revision (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    public_id         CHAR(36)    NOT NULL,
    tenant_id         BIGINT      NOT NULL,
    device_id         BIGINT      NOT NULL,
    desired_revision  BIGINT      NOT NULL,
    applied_revision  BIGINT      NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100),
    version           BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reader_revision_public (public_id),
    UNIQUE KEY uq_reader_revision_device (device_id),
    CONSTRAINT fk_reader_revision_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_reader_revision_device FOREIGN KEY (device_id) REFERENCES device (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE desired_member (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    public_id        CHAR(36)     NOT NULL,
    tenant_id        BIGINT       NOT NULL,
    device_id        BIGINT       NOT NULL,
    member_id        BIGINT       NOT NULL,
    revision         BIGINT       NOT NULL,
    device_user_id   VARCHAR(64)  NOT NULL,
    present_on_reader BOOLEAN     NOT NULL,
    reader_name      VARCHAR(31)  NOT NULL,
    reader_name_ex   VARCHAR(127),
    user_status      INT          NOT NULL,
    valid_from       VARCHAR(40)  NOT NULL,
    valid_to         VARCHAR(40)  NOT NULL,
    authority        VARCHAR(32)  NOT NULL,
    door_num         INT          NOT NULL,
    time_section_num INT          NOT NULL,
    face_sha256      CHAR(64)     NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    version          BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_desired_member_public (public_id),
    UNIQUE KEY uq_desired_member_device_member (device_id, member_id),
    KEY idx_desired_member_revision (device_id, revision),
    CONSTRAINT fk_desired_member_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_desired_member_device FOREIGN KEY (device_id) REFERENCES device (id),
    CONSTRAINT fk_desired_member_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reader_blocked_user (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    public_id      CHAR(36)    NOT NULL,
    tenant_id      BIGINT      NOT NULL,
    device_id      BIGINT      NOT NULL,
    device_user_id VARCHAR(64) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    version        BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_blocked_public (public_id),
    UNIQUE KEY uq_blocked_device_user (device_id, device_user_id),
    CONSTRAINT fk_blocked_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_blocked_device FOREIGN KEY (device_id) REFERENCES device (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
