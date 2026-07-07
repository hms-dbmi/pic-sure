CREATE OR REPLACE FUNCTION dict.sync_is_queryable() RETURNS TRIGGER AS $$
DECLARE
    target_id INT := coalesce(NEW.concept_node_id, OLD.concept_node_id);
BEGIN
    IF (NEW.key IS NOT NULL AND NEW.key = 'values') OR (OLD.key IS NOT NULL AND OLD.key = 'values') THEN
        UPDATE dict.concept_node SET is_queryable = EXISTS (
            SELECT 1 FROM dict.concept_node_meta
            WHERE concept_node_id = target_id
              AND key = 'values' AND value <> ''
        )
        WHERE concept_node_id = target_id;
    END IF;
    RETURN coalesce(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_is_queryable
    AFTER INSERT OR UPDATE OR DELETE ON dict.concept_node_meta
    FOR EACH ROW EXECUTE FUNCTION dict.sync_is_queryable();
