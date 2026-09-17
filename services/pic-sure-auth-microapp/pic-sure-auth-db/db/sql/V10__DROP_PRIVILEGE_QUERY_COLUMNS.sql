-- Drop the legacy v2 query-shape columns queryTemplate and queryScope from privilege.
--
-- These columns described the retired Auth Microapp v2 query body. PSAMA stopped
-- mapping them when the v2 query code was removed, and ddl-auto is unset, so they
-- have been inert. Both are nullable, so the drop cannot fail on existing data.
-- Each drop is guarded on information_schema so the file stays re-runnable, because
-- MySQL has no ALTER TABLE ... DROP COLUMN IF EXISTS.
--
-- Run only after the legacy v2 authorization cleanup migration has run and the v2
-- application is retired. Dry-run against a scratch copy of an auth database before
-- running it anywhere real. If the runner rejects PREPARE/EXECUTE, replace both
-- blocks with a bare:
--   ALTER TABLE privilege DROP COLUMN queryTemplate, DROP COLUMN queryScope;

USE `auth`;

SET @dropQueryTemplate := (
    SELECT IF(COUNT(*) > 0, 'ALTER TABLE privilege DROP COLUMN queryTemplate', 'DO 0')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'privilege'
       AND COLUMN_NAME  = 'queryTemplate'
);
PREPARE dropQueryTemplateStmt FROM @dropQueryTemplate;
EXECUTE dropQueryTemplateStmt;
DEALLOCATE PREPARE dropQueryTemplateStmt;

SET @dropQueryScope := (
    SELECT IF(COUNT(*) > 0, 'ALTER TABLE privilege DROP COLUMN queryScope', 'DO 0')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'privilege'
       AND COLUMN_NAME  = 'queryScope'
);
PREPARE dropQueryScopeStmt FROM @dropQueryScope;
EXECUTE dropQueryScopeStmt;
DEALLOCATE PREPARE dropQueryScopeStmt;
