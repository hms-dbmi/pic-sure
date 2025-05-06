package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.entities.*;
import edu.harvard.dbmi.avillach.dump.util.MapExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class RemoteDictionaryRepository {

    private static final Logger log = LoggerFactory.getLogger(RemoteDictionaryRepository.class);
    private final NamedParameterJdbcTemplate template;

    @Autowired
    public RemoteDictionaryRepository(NamedParameterJdbcTemplate template) {
        this.template = template;
    }

    public LocalDateTime getUpdateTimestamp(String name) {
        String sql = "SELECT LAST_UPDATED FROM remote_dictionary WHERE NAME = :dictName";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("dictName", name);
        return template.queryForList(sql, params, LocalDateTime.class).stream().findFirst().orElse(LocalDateTime.MIN);
    }

    public void dropValuesForSite(String name) {
        String sql = """
            DELETE FROM concept_node__remote_dictionary
            WHERE REMOTE_DICTIONARY_ID = (
                SELECT REMOTE_DICTIONARY_ID
                FROM remote_dictionary
                WHERE NAME = :dictName
            )
            """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("dictName", name);
        int unpairedConcepts = template.update(sql, params);
        log.info("Unpaired {} concepts while dropping values for site {}", unpairedConcepts, name);
        sql = "DELETE FROM remote_dictionary WHERE NAME = :dictName";
        template.update(sql, params);
    }

    public void setUpdateTimestamp(String name, LocalDateTime updateTime) {
        String sql = """
                UPDATE remote_dictionary
                SET LAST_UPDATED = :updated
                WHERE NAME = :dictName
            """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("dictName", name).addValue("updated", updateTime);
        template.update(sql, params);
    }

    /**
     * WARNING: If you invoke this method in a non-aggregate dictionary environment, it will wipe your database This function prunes all
     * facets, concepts, categories and their metas that are not associated with a remote dictionary.
     */
    public void pruneHangingEntries() {
        // delete all facet concepts that aren't associated with a concept from a remote dict
        String facetConceptSQL = """
            DELETE FROM facet__concept_node
            WHERE NOT EXISTS (
                SELECT 1
                FROM concept_node__remote_dictionary
                WHERE facet__concept_node.concept_node_id = concept_node__remote_dictionary.concept_node_id
            )
            """;
        // delete all concept metas that aren't associated with a concept from a remote dict
        String conceptMetaSQL = """
            DELETE FROM concept_node_meta
            WHERE NOT EXISTS (
                SELECT 1
                FROM concept_node__remote_dictionary
                WHERE concept_node_meta.concept_node_id = concept_node__remote_dictionary.concept_node_id
            )
            """;
        // delete all facet metas that aren't associated with a concept from a remote dict
        String facetMetaSQL = """
            DELETE FROM facet_meta
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet__concept_node
                    INNER JOIN concept_node__remote_dictionary remote ON remote.concept_node_id = facet__concept_node.concept_node_id
                WHERE facet__concept_node.facet_id = facet_meta.facet_id
            )
            """;
        // delete all facets that aren't associated with a concept from a remote dict
        String facetSQL = """
            DELETE FROM facet
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet__concept_node
                    INNER JOIN concept_node__remote_dictionary remote ON remote.concept_node_id = facet__concept_node.concept_node_id
                WHERE facet__concept_node.facet_id = facet.facet_id
            )
            """;
        // delete all concepts that aren't associated with a concept from a remote dict
        // this needs to be done recursively so that non leaf nodes are released as well
        String conceptSQL = """
            WITH RECURSIVE delete_concepts_until_empty AS (
                DELETE FROM concept_node
                WHERE
                    NOT EXISTS (
                        SELECT 1
                        FROM concept_node child
                        WHERE child.parent_id = concept_node.concept_node_id
                    )
                    AND
                    NOT EXISTS (
                        SELECT 1
                        FROM concept_node__remote_dictionary
                        WHERE concept_node.concept_node_id = concept_node__remote_dictionary.concept_node_id
                    )
                RETURNING concept_node_id
            )
            -- Recursive part continues until no more rows are deleted
            SELECT COUNT(*) FROM delete_concepts_until_empty;
            """;
        // delete facet metas that belong to categories with no facets
        String facetCategoryMetaSQL = """
            DELETE FROM facet_category_meta
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet
                WHERE facet.facet_category_id = facet_category_meta.facet_category_id
            )
            """;
        // delete facet categories with no facets
        String facetCategorySQL = """
            DELETE FROM facet_category
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet
                WHERE facet.facet_category_id = facet_category.facet_category_id
            )
            """;
        template.update(facetConceptSQL, new MapSqlParameterSource());
        template.update(conceptMetaSQL, new MapSqlParameterSource());
        template.update(facetMetaSQL, new MapSqlParameterSource());
        template.update(facetSQL, new MapSqlParameterSource());
        template.queryForObject(conceptSQL, new MapSqlParameterSource(), Integer.class);
        template.update(facetCategoryMetaSQL, new MapSqlParameterSource());
        template.update(facetCategorySQL, new MapSqlParameterSource());
    }

    public void addConceptsForSite(String name, List<ConceptNodeDump> concepts) {
        // initialize datasets if DNE
        createEmptyDatasets(concepts.stream().map(ConceptNodeDump::datasetRef).toList());
        Map<String, Integer> datasets = getDatasetRefs();

        // add the concepts one tier at a time
        Set<Integer> allConcepts = addConcepts(concepts, datasets);

        // get the ID of the remote data set being updated
        String remoteIDQuery = "SELECT REMOTE_DICTIONARY_ID FROM remote_dictionary WHERE NAME = :name";
        Integer siteId = template.queryForObject(remoteIDQuery, new MapSqlParameterSource("name", name), Integer.class);

        // for each
        List<Integer[]> pairs = allConcepts.stream().map(id -> new Integer[] {id, siteId}).toList();
        String pairSQL = "INSERT INTO concept_node__remote_dictionary (CONCEPT_NODE_ID, REMOTE_DICTIONARY_ID) VALUES :pairs";
        template.update(pairSQL, new MapSqlParameterSource("pairs", pairs));
    }

    private Set<Integer> addConcepts(List<ConceptNodeDump> concepts, Map<String, Integer> datasets) {
        String insertSQL = """
            INSERT INTO concept_node
                (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH, PARENT_ID)
            VALUES (:datasetID, :name, :display, :conceptType, :conceptPath, :parentID)
            ON CONFLICT (md5(concept_path::text)) DO UPDATE SET NAME = EXCLUDED.NAME
            RETURNING CONCEPT_NODE_ID
            """;
        Set<Integer> allConcepts = new HashSet<>();
        List<ConceptNodeDump> currentTier = concepts;
        while (!currentTier.isEmpty()) {
            for (ConceptNodeDump concept : currentTier) {
                Integer conceptID = template.queryForObject(insertSQL, createParamMap(concept, datasets), Integer.class);
                concept.setConceptNodeId(conceptID);
                concept.children().forEach(ch -> ch.setParentId(conceptID));
                allConcepts.add(conceptID);
            }
            currentTier = currentTier.stream().map(ConceptNodeDump::children).flatMap(List::stream).toList();
        }
        return allConcepts;
    }

    private MapSqlParameterSource createParamMap(ConceptNodeDump concept, Map<String, Integer> datasets) {
        return new MapSqlParameterSource().addValue("datasetID", datasets.get(concept.datasetRef())).addValue("name", concept.name())
            .addValue("display", concept.display()).addValue("conceptType", concept.conceptType())
            .addValue("conceptPath", concept.conceptPath()).addValue("parentID", concept.parentId());
    }

    private Map<String, Integer> getDatasetRefs() {
        String sql = "SELECT REF, DATASET_ID FROM dataset";
        return template.query(sql, new MapSqlParameterSource(), new MapExtractor("REF", "DATASET_ID")).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> Integer.parseInt(e.getValue())));
    }

    private void createEmptyDatasets(List<String> datasets) {
        String sql = """
            INSERT INTO dataset (REF, FULL_NAME, ABBREVIATION)
            VALUES (:ref, :ref, :ref)
            ON CONFLICT (REF) DO NOTHING
            """;
        MapSqlParameterSource[] params =
            datasets.stream().map(ds -> new MapSqlParameterSource("ref", ds)).toArray(MapSqlParameterSource[]::new);
        template.batchUpdate(sql, params);
    }

    public void addFacetCategoriesForSite(String name, List<FacetCategoryDump> categories) {

    }

    public void addFacetsForSite(String name, List<FacetDump> facets) {

    }

    public void addFacetCategoryMetasForSite(String name, List<FacetCategoryMetaDump> categoryMetas) {

    }

    public void addFacetMetasForSite(String name, List<FacetMetaDump> categoryMetas) {

    }

    public void addConceptMetasForSite(String name, List<ConceptNodeMetaDump> conceptMetas) {

    }

    public void addFacetConceptPairsForSite(String name, List<FacetConceptPair> pairs) {

    }
}
