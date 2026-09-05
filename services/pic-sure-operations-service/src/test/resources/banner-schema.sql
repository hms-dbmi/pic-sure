USE picsure;

CREATE TABLE banner_occurrence
(
    uuid                 BINARY(16)   NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    html_content         TEXT         NOT NULL,
    title                VARCHAR(120) DEFAULT NULL,
    appearance           VARCHAR(16)  NOT NULL,
    icon                 VARCHAR(16)  NOT NULL,
    dismissible          BOOLEAN      NOT NULL DEFAULT TRUE,
    audience             VARCHAR(16)  NOT NULL,
    placement            VARCHAR(32)  NOT NULL,
    page_targets         JSON         NOT NULL,
    start_at             DATETIME(6)  DEFAULT NULL,
    end_at               DATETIME(6)  DEFAULT NULL,
    priority             INT          DEFAULT NULL,
    presentation_hash    CHAR(64)     NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    created_by           VARCHAR(255) NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    updated_by           VARCHAR(255) NOT NULL,
    published_at         DATETIME(6)  DEFAULT NULL,
    published_by         VARCHAR(255) DEFAULT NULL,
    disabled_at          DATETIME(6)  DEFAULT NULL,
    disabled_by          VARCHAR(255) DEFAULT NULL,
    archived_at          DATETIME(6)  DEFAULT NULL,
    archived_by          VARCHAR(255) DEFAULT NULL,
    restored_from_uuid   BINARY(16)   DEFAULT NULL,
    PRIMARY KEY (uuid),
    CONSTRAINT fk_banner_occurrence_restore
        FOREIGN KEY (restored_from_uuid) REFERENCES banner_occurrence (uuid),
    INDEX idx_banner_occurrence_active (status, start_at, end_at, priority),
    INDEX idx_banner_occurrence_priority (priority)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE banner_version
(
    uuid                 BINARY(16)   NOT NULL,
    banner_uuid          BINARY(16)   NOT NULL,
    version_number       INT          NOT NULL,
    html_content         TEXT         NOT NULL,
    title                VARCHAR(120) DEFAULT NULL,
    appearance           VARCHAR(16)  NOT NULL,
    icon                 VARCHAR(16)  NOT NULL,
    dismissible          BOOLEAN      NOT NULL,
    audience             VARCHAR(16)  NOT NULL,
    placement            VARCHAR(32)  NOT NULL,
    page_targets         JSON         NOT NULL,
    start_at             DATETIME(6)  DEFAULT NULL,
    end_at               DATETIME(6)  DEFAULT NULL,
    presentation_hash    CHAR(64)     NOT NULL,
    effective_at         DATETIME(6)  NOT NULL,
    actor                VARCHAR(255) NOT NULL,
    PRIMARY KEY (uuid),
    CONSTRAINT fk_banner_version_occurrence
        FOREIGN KEY (banner_uuid) REFERENCES banner_occurrence (uuid),
    CONSTRAINT uq_banner_version_number UNIQUE (banner_uuid, version_number)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE banner_priority_allocator
(
    id            TINYINT NOT NULL,
    next_priority INT     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_banner_priority_allocator_singleton CHECK (id = 1)
) ENGINE = InnoDB;

-- The banner tables are created empty in this same migration, so the allocator starts at 1.
INSERT INTO banner_priority_allocator (id, next_priority)
VALUES (1, 1);
