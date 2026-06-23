-- Add is_queryable boolean to concept_node for fast displayability filtering.
-- Replaces the expensive JOIN concept_node_meta WHERE key='values' AND value<>''
-- that was costing 932K index lookups per facet query.

ALTER TABLE concept_node ADD COLUMN IF NOT EXISTS is_queryable BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE concept_node cn SET is_queryable = TRUE
WHERE EXISTS (
    SELECT 1 FROM concept_node_meta cnm
    WHERE cnm.concept_node_id = cn.concept_node_id
      AND cnm.key = 'values' AND cnm.value <> ''
);

CREATE INDEX IF NOT EXISTS idx_concept_node_queryable
    ON concept_node (concept_node_id)
    WHERE is_queryable = TRUE;
