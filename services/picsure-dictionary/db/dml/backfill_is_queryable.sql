-- Backfill/resync is_queryable for all existing concept_node rows.
--
-- Run this once after deploying V7__add_queryable_column.sql and V8__add_is_queryable_trigger.sql.
-- The trigger (trg_sync_is_queryable) maintains the column for all future ETL operations,
-- but existing rows need a one-time update since the column was added with DEFAULT FALSE.
--
-- Safe to re-run if concept_node is truncated/reloaded, or if is_queryable needs resyncing.
-- The WHERE guard skips rows already correct, avoiding unnecessary writes on large tables.
--
-- NOTE: uses value <> '' to match the trigger condition exactly (V8__add_is_queryable_trigger.sql).
-- Expects the dict schema search_path to be set (or qualify as dict.concept_node below).

UPDATE concept_node cn
SET is_queryable = EXISTS (
    SELECT 1
    FROM concept_node_meta cnm
    WHERE cnm.concept_node_id = cn.concept_node_id
      AND cnm.key = 'values'
      AND cnm.value <> ''
)
WHERE cn.is_queryable IS DISTINCT FROM EXISTS (
    SELECT 1
    FROM concept_node_meta cnm
    WHERE cnm.concept_node_id = cn.concept_node_id
      AND cnm.key = 'values'
      AND cnm.value <> ''
);

-- Verification: compare counts
SELECT
    COUNT(*)                                     AS total_concepts,
    COUNT(*) FILTER (WHERE is_queryable = TRUE)  AS queryable_concepts,
    COUNT(*) FILTER (WHERE is_queryable = FALSE) AS non_queryable_concepts
FROM concept_node;

-- Mismatch check: should return 0
SELECT COUNT(*) AS mismatches
FROM concept_node cn
WHERE cn.is_queryable IS DISTINCT FROM EXISTS (
    SELECT 1
    FROM concept_node_meta cnm
    WHERE cnm.concept_node_id = cn.concept_node_id
      AND cnm.key = 'values'
      AND cnm.value <> ''
);
