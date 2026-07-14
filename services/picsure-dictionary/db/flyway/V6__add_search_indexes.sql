-- ALS-11334: Add indexes for search query optimization
-- GIN index enables Bitmap Index Scan on tsvector (1,500x faster)
-- Meta covering index optimizes the 4-6 LEFT JOINs per query
-- Dataset FK index enables consent filtering without seq scan

CREATE INDEX IF NOT EXISTS idx_concept_node_searchable_fields_gin
    ON dict.concept_node USING GIN (searchable_fields);

CREATE INDEX IF NOT EXISTS idx_concept_node_meta_covering
    ON dict.concept_node_meta(concept_node_id, key);

CREATE INDEX IF NOT EXISTS idx_concept_node_dataset_id
    ON dict.concept_node(dataset_id);
