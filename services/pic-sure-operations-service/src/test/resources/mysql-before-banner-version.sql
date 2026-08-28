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

INSERT INTO banner_occurrence (
    uuid, status, html_content, title, appearance, icon, dismissible, audience, placement, page_targets,
    start_at, priority, presentation_hash, created_at, created_by, updated_at, updated_by, published_at, published_by
) VALUES (
    UUID_TO_BIN('00000000-0000-0000-0000-000000000001'), 'PUBLISHED', '<p>Pre-migration bytes</p>',
    'Pre-migration title', 'WARNING', 'WARNING', FALSE, 'SIGNED_IN', 'SITE_TOP',
    JSON_ARRAY(JSON_OBJECT('kind', 'EXACT', 'path', '/help')), '2026-08-27 12:00:00.000000', 1,
    REPEAT('a', 64), '2026-08-27 11:00:00.000000', 'creator', '2026-08-27 12:00:00.000000', 'publisher',
    '2026-08-27 12:00:00.000000', 'publisher'
);
