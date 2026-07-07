ALTER TABLE dict.concept_node ADD COLUMN concept_path_md5 text GENERATED ALWAYS AS (md5(concept_path::text)) STORED;
CREATE UNIQUE INDEX idx_concept_node_md5 ON dict.concept_node(concept_path_md5);

-- backwards compatible change, keep db version at 3
UPDATE dict.update_info SET DATABASE_VERSION = 3;