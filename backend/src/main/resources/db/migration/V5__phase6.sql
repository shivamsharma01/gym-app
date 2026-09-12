-- Phase 6: public gym profile, enquiries, notifications, announcements.

CREATE TABLE gym_profile (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)     NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    tagline     VARCHAR(200),
    about       VARCHAR(4000),
    phone       VARCHAR(32),
    email       VARCHAR(200),
    address     VARCHAR(300),
    hours       VARCHAR(300),
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_gym_profile_public_id (public_id),
    UNIQUE KEY uq_gym_profile_tenant (tenant_id),
    CONSTRAINT fk_gym_profile_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE enquiry (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    public_id      CHAR(36)      NOT NULL,
    tenant_id      BIGINT        NOT NULL,
    name           VARCHAR(150)  NOT NULL,
    email          VARCHAR(200)  NOT NULL,
    phone          VARCHAR(32),
    message        VARCHAR(2000) NOT NULL,
    plan_interest  VARCHAR(120),
    status         VARCHAR(16)   NOT NULL,
    staff_notes    VARCHAR(1000),
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    version        BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_enquiry_public_id (public_id),
    KEY idx_enquiry_tenant_status (tenant_id, status, created_at),
    CONSTRAINT fk_enquiry_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE notification_template (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)      NOT NULL,
    tenant_id   BIGINT        NOT NULL,
    template_key VARCHAR(64)  NOT NULL,
    channel     VARCHAR(16)   NOT NULL,
    subject     VARCHAR(200),
    body        VARCHAR(4000) NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_notification_template_public_id (public_id),
    UNIQUE KEY uq_notification_template_key (tenant_id, template_key, channel),
    CONSTRAINT fk_notification_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbound_notification (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    public_id      CHAR(36)      NOT NULL,
    tenant_id      BIGINT        NOT NULL,
    channel        VARCHAR(16)   NOT NULL,
    template_key   VARCHAR(64),
    recipient      VARCHAR(200)  NOT NULL,
    subject        VARCHAR(200),
    body           VARCHAR(4000) NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    attempt_count  INT           NOT NULL,
    last_error     VARCHAR(500),
    sent_at        DATETIME(6),
    member_id      BIGINT,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    version        BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbound_notification_public_id (public_id),
    KEY idx_outbound_notification_tenant (tenant_id, created_at),
    CONSTRAINT fk_outbound_notification_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE announcement (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    public_id   CHAR(36)      NOT NULL,
    tenant_id   BIGINT        NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(4000) NOT NULL,
    published   BOOLEAN       NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    version     BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_announcement_public_id (public_id),
    KEY idx_announcement_tenant (tenant_id, published, created_at),
    CONSTRAINT fk_announcement_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO permission (public_id, name, description, created_at, updated_at, created_by, updated_by, version)
VALUES
    (UUID(), 'ENQUIRY_VIEW',   'View public enquiries',   NOW(6), NOW(6), 'system', 'system', 0),
    (UUID(), 'ENQUIRY_MANAGE', 'Update public enquiries', NOW(6), NOW(6), 'system', 'system', 0);

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
WHERE r.name IN ('SUPER_ADMIN', 'GYM_OWNER', 'GYM_ADMIN')
  AND p.name IN ('ENQUIRY_VIEW', 'ENQUIRY_MANAGE');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
WHERE r.name IN ('STAFF', 'FRONT_DESK')
  AND p.name IN ('ENQUIRY_VIEW', 'ENQUIRY_MANAGE');
