package edu.harvard.dbmi.avillach.dictionary.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@DependsOnDatabaseInitialization
public class SchemaDetector {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaDetector.class);

    private static final String CHECK_COLUMN_SQL = """
        SELECT EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = ?
              AND column_name = 'is_queryable'
        )
        """;

    private final boolean conceptNodeQueryable;
    private final boolean fcnQueryable;

    @Autowired
    public SchemaDetector(JdbcTemplate jdbcTemplate) {
        boolean cnQueryable = false;
        boolean fcnQ = false;
        try {
            cnQueryable = Objects.requireNonNullElse(jdbcTemplate.queryForObject(CHECK_COLUMN_SQL, Boolean.class, "concept_node"), false);
            fcnQ = Objects.requireNonNullElse(jdbcTemplate.queryForObject(CHECK_COLUMN_SQL, Boolean.class, "facet__concept_node"), false);
        } catch (Exception e) {
            LOG.warn("Schema detection failed ({}), defaulting to legacy query paths", e.getMessage());
        }
        this.conceptNodeQueryable = cnQueryable;
        this.fcnQueryable = fcnQ;
        LOG.info(
            "Schema detection: concept_node.is_queryable {} | facet__concept_node.is_queryable {}",
            conceptNodeQueryable ? "found" : "NOT FOUND — legacy fallback", fcnQueryable ? "found" : "NOT FOUND — legacy fallback"
        );
    }

    /**
     * Returns a SQL WHERE clause fragment for concept_node queryability. Fast path: {@code alias.is_queryable = TRUE} Legacy fallback:
     * EXISTS subquery against concept_node_meta.
     */
    public String conceptNodeQueryableClause(String alias) {
        if (conceptNodeQueryable) {
            return alias + ".is_queryable = TRUE";
        }
        return "EXISTS (SELECT 1 FROM concept_node_meta cnm_q WHERE cnm_q.concept_node_id = " + alias
            + ".concept_node_id AND cnm_q.key = 'values' AND cnm_q.value <> '')";
    }

    /**
     * Returns a SQL WHERE clause fragment for facet__concept_node queryability. Fast path: {@code alias.is_queryable = TRUE} Legacy
     * fallback: EXISTS subquery joining concept_node_meta via concept_node.
     */
    public String fcnQueryableClause(String alias) {
        if (fcnQueryable) {
            return alias + ".is_queryable = TRUE";
        }
        return "EXISTS (SELECT 1 FROM concept_node_meta cnm_q WHERE cnm_q.concept_node_id = " + alias
            + ".concept_node_id AND cnm_q.key = 'values' AND cnm_q.value <> '')";
    }
}
