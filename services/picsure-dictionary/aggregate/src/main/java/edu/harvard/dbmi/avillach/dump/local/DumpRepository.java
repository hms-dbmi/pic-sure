package edu.harvard.dbmi.avillach.dump.local;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeDump;
import edu.harvard.dbmi.avillach.dump.entities.DumpRow;
import edu.harvard.dbmi.avillach.dump.entities.FacetDump;
import edu.harvard.dbmi.avillach.dump.local.extractor.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Gatherer;
import java.util.stream.Gatherers;

@Repository
public class DumpRepository {
    private final NamedParameterJdbcTemplate template;
    private final ConceptNodeMetaDumpMapper conceptMetaMapper;
    private final ConceptNodeDumpExtractor conceptNodeDumpExtractor;
    private final FacetCategoryDumpMapper facetCategoryMapper;
    private final FacetCategoryMetaDumpMapper facetCategoryMetaMapper;
    private final FacetMetaDumpMapper facetMetaMapper;
    private final FacetDumpMapper facetMapper;
    private final FacetConceptPairMapper facetConceptPairMapper;
    private final MapSqlParameterSource NO_PARAMS = new MapSqlParameterSource();

    @Autowired
    public DumpRepository(
        NamedParameterJdbcTemplate template, ConceptNodeMetaDumpMapper conceptMetaMapper, ConceptNodeDumpExtractor conceptNodeDumpExtractor,
        FacetCategoryDumpMapper facetCategoryMapper, FacetCategoryMetaDumpMapper facetCategoryMetaMapper,
        FacetMetaDumpMapper facetMetaMapper, FacetDumpMapper facetMapper, FacetConceptPairMapper facetConceptPairMapper
    ) {
        this.template = template;
        this.conceptMetaMapper = conceptMetaMapper;
        this.conceptNodeDumpExtractor = conceptNodeDumpExtractor;
        this.facetCategoryMapper = facetCategoryMapper;
        this.facetCategoryMetaMapper = facetCategoryMetaMapper;
        this.facetMetaMapper = facetMetaMapper;
        this.facetMapper = facetMapper;
        this.facetConceptPairMapper = facetConceptPairMapper;
    }

    public LocalDateTime getLastUpdated() {
        String sql = "SELECT LAST_UPDATED FROM update_info ORDER BY LAST_UPDATED DESC LIMIT 1";
        return template.queryForList(sql, NO_PARAMS, LocalDateTime.class).stream().findFirst().orElse(LocalDateTime.now());
    }

    public List<ConceptNodeDump> getAllConcepts() {
        List<ConceptNodeDump> roots = new ArrayList<>();
        Map<Integer, ConceptNodeDump> parents = new HashMap<>();
        String sql = """
            SELECT
                dataset.REF, concept_node.CONCEPT_NODE_ID,
                concept_node.NAME, concept_node.DISPLAY,
                concept_node.CONCEPT_TYPE, concept_node.CONCEPT_PATH,
                parent.CONCEPT_NODE_ID AS PARENT_ID
            FROM
                concept_node
                LEFT JOIN concept_node parent ON concept_node.parent_id = parent.concept_node_id
                JOIN dataset ON concept_node.dataset_id = dataset.dataset_id
            ORDER BY length(concept_node.CONCEPT_PATH) ASC
            """;
        List<ConceptNodeDump> nodes = template.query(sql, NO_PARAMS, conceptNodeDumpExtractor);
        for (ConceptNodeDump node : nodes) {
            parents.put(node.conceptNodeId(), node);
            if (node.parentId() == 0) {
                roots.add(node);
            } else {
                parents.get(node.parentId()).addChild(node);
            }
        }
        return roots;
    }

    public List<? extends DumpRow> getAllConceptNodeMetas() {
        String sql = """
            SELECT CONCEPT_PATH, KEY, VALUE
            FROM concept_node_meta
                INNER JOIN concept_node ON concept_node_meta.CONCEPT_NODE_ID = concept_node.CONCEPT_NODE_ID
            """;
        return template.query(sql, NO_PARAMS, conceptMetaMapper);
    }

    public List<? extends DumpRow> getAllFacets() {
        String sql = """
            SELECT
                facet.NAME, facet.DISPLAY, facet.DESCRIPTION,
                facet_category.NAME AS FACET_CATEGORY_NAME, FACET_ID, PARENT_ID
            FROM facet
                JOIN facet_category ON facet.facet_category_id = facet_category.facet_category_id
            WHERE PARENT_ID IS NULL
            """;
        List<FacetDump> roots = template.query(sql, NO_PARAMS, facetMapper);
        sql = """
            SELECT
                facet.NAME, facet.DISPLAY, facet.DESCRIPTION,
                facet_category.NAME AS FACET_CATEGORY_NAME, FACET_ID, PARENT_ID
            FROM facet
                JOIN facet_category ON facet.facet_category_id = facet_category.facet_category_id
            WHERE PARENT_ID IN (:parentIDs)
            """;
        HashMap<Integer, FacetDump> allFacets =
            new HashMap<>(roots.stream().collect(Collectors.toMap(FacetDump::facetID, Function.identity())));
        List<FacetDump> currentTier = roots;
        while (!currentTier.isEmpty()) {
            List<Integer> parentIds = currentTier.stream().map(FacetDump::facetID).toList();
            List<FacetDump> children = template.query(sql, new MapSqlParameterSource("parentIDs", parentIds), facetMapper);
            for (FacetDump child : children) {
                allFacets.get(child.parentID()).addChild(child);
                allFacets.put(child.facetID(), child);
            }
            currentTier = children;
        }
        return roots;
    }

    public List<? extends DumpRow> getAllFacetCategories() {
        String sql = """
            SELECT NAME, DISPLAY, DESCRIPTION
            FROM facet_category
            """;
        return template.query(sql, NO_PARAMS, facetCategoryMapper);
    }

    public List<? extends DumpRow> getAllFacetMetas() {
        String sql = """
            SELECT facet.NAME AS FACET, facet_category.NAME AS FACET_CATEGORY, KEY, VALUE
            FROM facet_meta
                JOIN facet ON facet.facet_id = facet_meta.facet_id
                JOIN facet_category ON facet.facet_category_id = facet_category.facet_category_id
            """;
        return template.query(sql, NO_PARAMS, facetMetaMapper);
    }

    public List<? extends DumpRow> getAllFacetCategoryMetas() {
        String sql = """
            SELECT NAME, KEY, VALUE
            FROM facet_category_meta
                JOIN facet_category ON facet_category.facet_category_id = facet_category_meta.facet_category_id
            """;
        return template.query(sql, NO_PARAMS, facetCategoryMetaMapper);
    }

    public List<? extends DumpRow> getAllFacetConceptPairs() {
        String sql = """
            SELECT
                facet.name AS FACET,
                facet_category.name AS FACET_CATEGORY,
                concept_node.CONCEPT_PATH AS CONCEPT_PATH
            FROM
                facet__concept_node fcn
                JOIN facet ON fcn.facet_id = facet.facet_id
                JOIN facet_category ON facet.facet_category_id = facet_category.facet_category_id
                JOIN concept_node ON fcn.concept_node_id = concept_node.concept_node_id
            """;
        return template.query(sql, NO_PARAMS, facetConceptPairMapper);
    }

    public Integer getDatabaseVersion() {
        String sql = "SELECT DATABASE_VERSION FROM update_info";
        return template.queryForObject(sql, NO_PARAMS, Integer.class);
    }
}
