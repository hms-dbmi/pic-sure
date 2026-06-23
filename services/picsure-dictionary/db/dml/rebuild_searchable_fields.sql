-- ============================================================
-- rebuild_searchable_fields.sql
--
-- Standalone DML script that rebuilds the searchable_fields
-- tsvector column on concept_node. This is the exact SQL that
-- the dictionaryweights micro app generates from weights.csv.
--
-- Source of truth: dictionaryweights/weights.csv
-- If weights.csv changes, this script must be regenerated.
--
-- Usage:
--   psql -d dictionary -f rebuild_searchable_fields.sql
--   (or run block-by-block in a SQL client)
--
-- Weight tiers (A = highest priority, D = lowest):
--   A: concept_node.DISPLAY, concept_node.CONCEPT_PATH
--   B: dataset.FULL_NAME, dataset.DESCRIPTION
--   C: parent.DISPLAY, grandparent.DISPLAY
--   D: concept_node_meta_str.values (whitelisted keys, values capped at 60K)
--
-- Uses 'english' language config to match query-layer
-- to_tsquery('english', ...) stemming behavior.
-- ============================================================

UPDATE concept_node
SET SEARCHABLE_FIELDS = data_table.search_vector
FROM
(
    SELECT
        setweight(to_tsvector('english', replace(coalesce(concept_node.DISPLAY, ''), '_', ' ')), 'A') ||
        setweight(to_tsvector('english', replace(coalesce(concept_node.CONCEPT_PATH, ''), '_', ' ')), 'A') ||
        setweight(to_tsvector('english', replace(coalesce(dataset.FULL_NAME, ''), '_', ' ')), 'B') ||
        setweight(to_tsvector('english', replace(coalesce(dataset.DESCRIPTION, ''), '_', ' ')), 'B') ||
        setweight(to_tsvector('english', replace(coalesce(parent.DISPLAY, ''), '_', ' ')), 'C') ||
        setweight(to_tsvector('english', replace(coalesce(grandparent.DISPLAY, ''), '_', ' ')), 'C') ||
        setweight(to_tsvector('english', replace(coalesce(concept_node_meta_str.values, ''), '_', ' ')), 'D')
        AS search_vector,
        concept_node.concept_node_id AS search_key
    FROM
        concept_node
        LEFT JOIN
        (
            SELECT
                concept_node_id AS id,
                string_agg(DISTINCT safe_value, ' ') AS values
            FROM (
                SELECT concept_node_id, value AS safe_value
                FROM concept_node_meta
                WHERE value <> ''
                  AND key IN ('description','derived_values','variable_type','comment','domain','Question','question','unit')
                UNION ALL
                SELECT concept_node_id, left(value, 60000) AS safe_value
                FROM concept_node_meta
                WHERE value <> '' AND key = 'values'
            ) AS filtered_meta
            GROUP BY
                concept_node_id
        ) AS concept_node_meta_str ON concept_node_meta_str.id = concept_node.concept_node_id
        LEFT JOIN dataset AS study ON concept_node.dataset_id = study.dataset_id
        LEFT JOIN concept_node AS parent ON concept_node.parent_id = parent.concept_node_id
        LEFT JOIN concept_node AS grandparent ON parent.parent_id = grandparent.concept_node_id
        LEFT JOIN (
            SELECT
                dataset_node.name AS NAME,
                dataset_node.display AS FULL_NAME,
                dataset_node.concept_path AS CONCEPT_PATH,
                description_concept_node_meta.VALUE AS DESCRIPTION
            FROM
                concept_node AS dataset_node
                JOIN concept_node_meta AS description_concept_node_meta ON description_concept_node_meta.KEY = 'description' AND description_concept_node_meta.concept_node_id = dataset_node.concept_node_id
        ----------------------------------------------------------------------------------------------------
        -- This join is saying "find me the concept node that shares the first two nodes of this concept
        -- and then stops". So for /a/b/c/d/e/f/, it finds concept /a/b/
        ----------------------------------------------------------------------------------------------------
        ) AS dataset ON dataset.concept_path = REGEXP_REPLACE(concept_node.concept_path, '(^\\[^\\]*\\[^\\]*\\)(.*$)', '\1')
) AS data_table
WHERE concept_node.concept_node_id = data_table.search_key;
