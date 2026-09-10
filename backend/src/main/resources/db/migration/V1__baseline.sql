-- Smart Gym Management Platform — Phase 1 baseline schema.
-- Schema is owned exclusively by Flyway. Every table carries a surrogate BIGINT id and a public_id
-- (UUID) for external references. Tenant-owned tables reference tenant(id).

CREATE TABLE tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)     NOT NULL,
    name        VARCHAR(150) NOT NULL,
    slug        VARCHAR(80)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_public_id (public_id),
    UNIQUE KEY uq_tenant_slug (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)     NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(200),
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_permission_public_id (public_id),
    UNIQUE KEY uq_permission_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)     NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(200),
    tenant_id   BIGINT,
    is_system   BOOLEAN      NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_role_public_id (public_id),
    UNIQUE KEY uq_role_name_tenant (name, tenant_id),
    CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permission (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE admin_user (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    public_id             CHAR(36)     NOT NULL,
    tenant_id             BIGINT,
    username              VARCHAR(100) NOT NULL,
    email                 VARCHAR(200) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(150) NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          DATETIME(6),
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    version               BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_user_public_id (public_id),
    UNIQUE KEY uq_admin_user_username (username),
    UNIQUE KEY uq_admin_user_email (email),
    KEY idx_admin_user_tenant (tenant_id),
    CONSTRAINT fk_admin_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES admin_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE refresh_token (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)    NOT NULL,
    user_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    replaced_by CHAR(36),
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_refresh_token_public_id (public_id),
    UNIQUE KEY uq_refresh_token_hash (token_hash),
    KEY idx_refresh_token_user (user_id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES admin_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE audit_log (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    public_id      CHAR(36)    NOT NULL,
    tenant_id      BIGINT,
    actor_user_id  BIGINT,
    actor_username VARCHAR(100),
    action         VARCHAR(80) NOT NULL,
    resource_type  VARCHAR(80),
    resource_id    VARCHAR(80),
    result         VARCHAR(20) NOT NULL,
    ip_address     VARCHAR(64),
    user_agent     VARCHAR(256),
    correlation_id VARCHAR(36),
    details        TEXT,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_audit_log_public_id (public_id),
    KEY idx_audit_log_tenant_time (tenant_id, created_at),
    KEY idx_audit_log_action (action)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
