-- Backfill/resync is_queryable on facet__concept_node from concept_node.
--
-- Run once after deploying V10__add_fcn_is_queryable.sql.
-- Triggers maintain the column going forward:
--   trg_set_fcn_is_queryable (BEFORE INSERT/UPDATE on fcn)
--   trg_cascade_is_queryable_to_fcn (AFTER UPDATE OF is_queryable on concept_node)
--
-- Safe to re-run if facet__concept_node is truncated/reloaded.
-- WHERE guard skips rows already correct, avoiding unnecessary writes.

UPDATE dict.facet__concept_node fcn
SET is_queryable = cn.is_queryable
FROM dict.concept_node cn
WHERE cn.concept_node_id = fcn.concept_node_id
  AND fcn.is_queryable IS DISTINCT FROM cn.is_queryable;

-- Verification: compare counts
SELECT
    COUNT(*) AS total_fcn,
    COUNT(*) FILTER (WHERE is_queryable = TRUE) AS queryable,
    COUNT(*) FILTER (WHERE is_queryable = FALSE) AS non_queryable
FROM dict.facet__concept_node;

-- Mismatch check: should return 0
SELECT COUNT(*) AS mismatches
FROM dict.facet__concept_node fcn
JOIN dict.concept_node cn ON cn.concept_node_id = fcn.concept_node_id
WHERE fcn.is_queryable IS DISTINCT FROM cn.is_queryable;
