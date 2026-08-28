USE picsure;

CREATE TABLE banner_priority_allocator
(
    id            TINYINT NOT NULL,
    next_priority INT     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_banner_priority_allocator_singleton CHECK (id = 1)
) ENGINE = InnoDB;

INSERT INTO banner_priority_allocator (id, next_priority)
SELECT 1, COALESCE(MAX(priority), 0) + 1
FROM banner_occurrence
WHERE status = 'PUBLISHED'
  AND (end_at IS NULL OR end_at > UTC_TIMESTAMP(6));
