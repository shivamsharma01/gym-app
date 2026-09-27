-- Member face photos (one per member, versioned) and per-device face sync state.

CREATE TABLE member_face (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    public_id         CHAR(36)     NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    member_id         BIGINT       NOT NULL,
    object_key        VARCHAR(255) NOT NULL,
    sha256            CHAR(64)     NOT NULL,
    face_version      INT          NOT NULL,
    size_bytes        INT          NOT NULL,
    source            VARCHAR(16)  NOT NULL,
    source_device_id  BIGINT,
    changed_at        DATETIME(6)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100),
    version           BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_face_public_id (public_id),
    UNIQUE KEY uq_member_face_member (member_id),
    KEY idx_member_face_tenant (tenant_id),
    CONSTRAINT fk_member_face_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_member_face_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_face_device FOREIGN KEY (source_device_id) REFERENCES device (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Face images uploaded by the gateway (device -> server), waiting to be attached to a member.
CREATE TABLE gateway_face_upload (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)     NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    gateway_id  BIGINT       NOT NULL,
    object_key  VARCHAR(255) NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    size_bytes  INT          NOT NULL,
    consumed    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_gateway_face_upload_public_id (public_id),
    KEY idx_gateway_face_upload_created (created_at),
    CONSTRAINT fk_gfu_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_gfu_gateway FOREIGN KEY (gateway_id) REFERENCES gateway (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE member
    ADD COLUMN profile_changed_at DATETIME(6) NULL AFTER creation_source;

UPDATE member SET profile_changed_at = updated_at WHERE profile_changed_at IS NULL;

ALTER TABLE member_device_mapping
    ADD COLUMN face_version_synced INT         NULL AFTER enrolled_at,
    ADD COLUMN face_sync_state     VARCHAR(16) NOT NULL DEFAULT 'NOT_SYNCED' AFTER face_version_synced,
    ADD COLUMN face_last_error     VARCHAR(500) NULL AFTER face_sync_state;
