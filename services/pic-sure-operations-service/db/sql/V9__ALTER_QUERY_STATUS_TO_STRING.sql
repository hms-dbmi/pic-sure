USE `picsure`;

-- ============================================================================
-- OPERATOR RUNBOOK -- READ BEFORE SCHEDULING. This migration BLOCKS WRITES to `query`.
-- ============================================================================
--
-- COST / LOCKING. An int -> varchar `MODIFY COLUMN` cannot be done in place: MySQL runs it as
-- ALGORITHM=COPY, i.e. a FULL TABLE REBUILD, and holds a metadata lock for the duration. Concurrent
-- DML is NOT permitted against `query` while it runs -- every query submission, status update and
-- named-dataset save that touches this table blocks until it finishes (and a long-running lock wait
-- surfaces to users as request timeouts, not as an error you will see in this script's output).
-- The `UPDATE` that follows is deliberately UNBATCHED: it is a single transaction over every row, so
-- it holds row locks on the whole table and produces one undo-log/binlog entry proportional to the
-- table size.
--
-- BEFORE YOU SCHEDULE: check the row count and the table size --
--     SELECT COUNT(*) FROM `query`;
--     SELECT table_rows, data_length, index_length FROM information_schema.tables
--      WHERE table_schema = 'picsure' AND table_name = 'query';
-- A small table (order 1e5 rows) finishes in seconds and can go in a normal deploy. A large one
-- (order 1e7+) needs a maintenance window, or an online-schema-change tool (gh-ost / pt-online-schema-change)
-- for the ALTER plus a batched, keyed UPDATE loop in place of the single statement below. Do not
-- discover which case you are in during the deploy.
--
-- DEPLOY ORDERING -- ATOMIC WITH THE CODE, IN BOTH DIRECTIONS. There is deliberately NO dual-read
-- shim in the entity: `Query.status` is either @Enumerated(ORDINAL) or STRING, never tolerant of both.
--   * Migration applied but OLD code still running -> ORDINAL reads hit a VARCHAR column -> every
--     status read fails.
--   * NEW code deployed but migration NOT applied -> STRING reads hit an int column -> same.
-- So this migration and the operations-service release that flips the annotation must land together,
-- and a ROLLBACK of that release must be paired with a reverse migration (widen back to int and
-- reverse the CASE) -- rolling the code back on its own re-breaks reads just as surely.
--
-- RE-RUN SAFETY. Idempotent. The ALTER is a no-op once the column is already VARCHAR(32), and the
-- UPDATE's ELSE branch leaves any value that is already a name untouched, so a partially applied or
-- repeated run cannot corrupt data. (`QueryStatusMigrationTest` in the operations-service suite
-- replays this file -- twice, among other cases -- against H2 in MySQL mode to keep that true.)
-- ============================================================================
--
-- `query`.`status` holds edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus. It was written by JPA's DEFAULT
-- enum mapping (the legacy entity carried no @Enumerated annotation at all), i.e. ORDINAL into an `int(11)` column --
-- so the meaning of every stored row was the DECLARATION ORDER of the enum, and inserting or reordering a constant
-- silently rewrote history. Query.java now declares @Enumerated(EnumType.STRING); this migration moves the data to match.
--
-- The ordinals below are the declaration order of the original enum
-- (git show 43d7622e:libs/pic-sure-commons/pic-sure-api-model/src/main/java/edu/harvard/dbmi/avillach/domain/PicSureStatus.java
--  -> `public enum PicSureStatus { QUEUED, PENDING, ERROR, AVAILABLE }`), which the contracts enum preserves verbatim:
--   0 = QUEUED, 1 = PENDING, 2 = ERROR, 3 = AVAILABLE.
--
-- Order matters: widening the int column FIRST makes MySQL rewrite each value as its decimal string ('0','1','2','3'),
-- which the UPDATE then maps onto the names. NULL statuses stay NULL (no CASE branch matches, and ELSE returns NULL);
-- any value that is already a name is left untouched by the ELSE, so re-running this is harmless.
ALTER TABLE `query` MODIFY COLUMN `status` VARCHAR(32);

UPDATE `query` SET `status` = CASE `status`
    WHEN '0' THEN 'QUEUED'
    WHEN '1' THEN 'PENDING'
    WHEN '2' THEN 'ERROR'
    WHEN '3' THEN 'AVAILABLE'
    ELSE `status`
END;
