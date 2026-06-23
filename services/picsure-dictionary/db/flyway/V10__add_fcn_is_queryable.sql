-- Add is_queryable column to facet__concept_node
-- Eliminates the expensive concept_node JOIN from every facet query (~2100ms → ~800ms)
-- Maintained by two triggers:
--   1. trg_set_fcn_is_queryable: sets value on INSERT/UPDATE from concept_node
--   2. trg_cascade_is_queryable_to_fcn: cascades concept_node.is_queryable changes


ALTER TABLE facet__concept_node
    ADD COLUMN IF NOT EXISTS is_queryable BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill from concept_node.is_queryable (populated by V7)
UPDATE facet__concept_node fcn
SET is_queryable = cn.is_queryable
FROM concept_node cn
WHERE cn.concept_node_id = fcn.concept_node_id;

-- Trigger 1: set is_queryable on fcn INSERT/UPDATE (covers ETL reload)
CREATE OR REPLACE FUNCTION dict.set_fcn_is_queryable() RETURNS TRIGGER AS $$
BEGIN
    NEW.is_queryable := (
        SELECT cn.is_queryable
        FROM dict.concept_node cn
        WHERE cn.concept_node_id = NEW.concept_node_id
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_set_fcn_is_queryable ON facet__concept_node;
CREATE TRIGGER trg_set_fcn_is_queryable
    BEFORE INSERT OR UPDATE ON facet__concept_node
    FOR EACH ROW EXECUTE FUNCTION dict.set_fcn_is_queryable();

-- Trigger 2: cascade concept_node.is_queryable changes to fcn
CREATE OR REPLACE FUNCTION dict.cascade_is_queryable_to_fcn() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.is_queryable IS DISTINCT FROM NEW.is_queryable THEN
        UPDATE dict.facet__concept_node
        SET is_queryable = NEW.is_queryable
        WHERE concept_node_id = NEW.concept_node_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_cascade_is_queryable_to_fcn ON concept_node;
CREATE TRIGGER trg_cascade_is_queryable_to_fcn
    AFTER UPDATE OF is_queryable ON concept_node
    FOR EACH ROW EXECUTE FUNCTION dict.cascade_is_queryable_to_fcn();

-- Partial indexes (equivalent to matview indexes from Step 17B)
CREATE INDEX IF NOT EXISTS idx_fcn_queryable_fid_cid
    ON facet__concept_node(facet_id, concept_node_id)
    WHERE is_queryable = TRUE;

CREATE INDEX IF NOT EXISTS idx_fcn_queryable_cid
    ON facet__concept_node(concept_node_id)
    WHERE is_queryable = TRUE;
