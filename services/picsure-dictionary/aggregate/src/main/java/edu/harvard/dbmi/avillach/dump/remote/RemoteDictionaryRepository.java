package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.entities.*;
import edu.harvard.dbmi.avillach.dump.util.MapExtractor;
import org.apache.catalina.util.ExactRateLimiter;
import org.apache.catalina.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;

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
        log.info("Pruning facet concepts");
        int pruned = template.update(facetConceptSQL, new MapSqlParameterSource());
        log.info("Pruned {} facet concepts", pruned);
        log.info("Pruning concept metas");
        pruned = template.update(conceptMetaSQL, new MapSqlParameterSource());
        log.info("Pruned {} facet metas", pruned);
        log.info("Pruning facet metas");
        pruned = template.update(facetMetaSQL, new MapSqlParameterSource());
        log.info("Pruned {} facet metas", pruned);
        log.info("Pruning facets");
        pruned = template.update(facetSQL, new MapSqlParameterSource());
        log.info("Pruned {} facets", pruned);
        log.info("Pruning concepts");
        template.queryForObject(conceptSQL, new MapSqlParameterSource(), Integer.class);
        log.info("Pruned {} concepts", pruned);
        log.info("Pruning facet category metas");
        pruned = template.update(facetCategoryMetaSQL, new MapSqlParameterSource());
        log.info("Pruned {} facet category metas", pruned);
        log.info("Pruning facet categories");
        pruned = template.update(facetCategorySQL, new MapSqlParameterSource());
        log.info("Pruned {} facet categories", pruned);
    }

    public void addConceptsForSite(String name, List<ConceptNodeDump> concepts) {
        log.info("Starting concept ingest for {}", name);
        // initialize datasets if DNE
        createEmptyDatasets(concepts.stream().map(ConceptNodeDump::datasetRef).distinct().toList());
        Map<String, Integer> datasets = getDatasetRefs();
        log.info("Initialized datasets");
        // add the concepts one tier at a time
        Set<Integer> allConcepts = addConcepts(concepts, datasets);

        // get the ID of the remote data set being updated
        String remoteIDQuery = "SELECT REMOTE_DICTIONARY_ID FROM remote_dictionary WHERE NAME = :name";
        Integer siteId = template.queryForObject(remoteIDQuery, new MapSqlParameterSource("name", name), Integer.class);

        log.info("Pairing added concepts to site {}", name);
        String pairSQL = "INSERT INTO concept_node__remote_dictionary (CONCEPT_NODE_ID, REMOTE_DICTIONARY_ID) VALUES (:nodeID, :dictID)";
        MapSqlParameterSource[] params = allConcepts.stream().map(id -> new MapSqlParameterSource("nodeID", id).addValue("dictID", siteId))
            .toArray(MapSqlParameterSource[]::new);
        template.batchUpdate(pairSQL, params);
        log.info("Done ingesting concepts for {}", name);
    }

    private Set<Integer> addConcepts(List<ConceptNodeDump> concepts, Map<String, Integer> datasets) {
        concepts.forEach(c -> c.setParentId(null));
        String childSQL = """
            INSERT INTO concept_node (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH, PARENT_ID)
            SELECT :datasetRef, :name, :display, :conceptType, :conceptPath, CONCEPT_NODE_ID
            FROM concept_node AS parent
            WHERE parent.CONCEPT_PATH_MD5 = md5(:parentPath::text)
            ON CONFLICT (concept_path_md5) DO NOTHING
            """;
        String rootSQL = """
            INSERT INTO concept_node (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH)
            VALUES (:datasetRef, :name, :display, :conceptType, :conceptPath)
            ON CONFLICT (concept_path_md5) DO NOTHING
            """;
        String idSQL = """
            WITH paths AS (SELECT unnest(:paths) AS CONCEPT)
            SELECT CONCEPT_NODE_ID
            FROM concept_node
                INNER JOIN paths ON concept_node.concept_path = paths.CONCEPT
            """;
        Set<Integer> allConcepts = new HashSet<>();

        int tier = 0;
        int count = 0;
        while (!concepts.isEmpty()) {
            log.info("Ingesting tier {}, starting concept count {}", tier++, count += concepts.size());
            concepts.forEach(concept -> concept.children().forEach(c -> c.setParentPath(concept.conceptPath())));
            String sql = concepts.getFirst().parentId() == null ? rootSQL : childSQL;
            for (List<ConceptNodeDump> conceptBucket : concepts.stream().gather(Gatherers.windowFixed(1000)).toList()) {
                template
                    .batchUpdate(sql, conceptBucket.stream().map(c -> createParamMap(c, datasets)).toArray(MapSqlParameterSource[]::new));
                MapSqlParameterSource params =
                    new MapSqlParameterSource("paths", conceptBucket.stream().map(ConceptNodeDump::conceptPath).toArray(String[]::new));
                List<Integer> conceptIds = template.queryForList(idSQL, params, Integer.class);
                allConcepts.addAll(conceptIds);
            }
            concepts = concepts.stream().map(ConceptNodeDump::children).flatMap(List::stream).toList();
            count += concepts.size();
        }
        return allConcepts;
    }

    private MapSqlParameterSource createParamMap(ConceptNodeDump concept, Map<String, Integer> datasets) {
        return new MapSqlParameterSource().addValue("datasetRef", datasets.get(concept.datasetRef())).addValue("name", concept.name())
            .addValue("display", concept.display()).addValue("conceptType", concept.conceptType())
            .addValue("conceptPath", concept.conceptPath()).addValue("parentPath", concept.parentPath());
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
        log.info("Starting facet category ingest for {}", name);
        String sql = """
            INSERT INTO facet_category (NAME, DISPLAY, DESCRIPTION)
            VALUES (:name, :display, :description)
            ON CONFLICT (NAME) DO NOTHING
            """;
        categories.stream()
            .map(
                c -> new MapSqlParameterSource().addValue("name", c.name()).addValue("display", c.display())
                    .addValue("description", c.description())
            ).forEach(params -> template.update(sql, params));
        log.info("Done ingesting facet categories for {}", name);
    }

    public void addFacetsForSite(String name, List<FacetDump> facets) {
        log.info("Starting facet ingest for {}", name);
        // set all parent names
        String sql = """
            INSERT INTO facet (NAME, DISPLAY, DESCRIPTION, FACET_CATEGORY_ID, PARENT_ID)
            SELECT :name, :display, :description, facet_category.FACET_CATEGORY_ID, parent.FACET_ID
            FROM facet_category
                LEFT JOIN facet AS parent ON parent.FACET_CATEGORY_ID = facet_category.FACET_CATEGORY_ID AND parent.name = :parentName
            WHERE facet_category.NAME = :facetCategoryName
            ON CONFLICT DO NOTHING
            """;
        List<FacetDump> currentTier = facets;
        while (!currentTier.isEmpty()) {
            for (FacetDump facet : currentTier) {
                MapSqlParameterSource params = new MapSqlParameterSource().addValue("name", facet.name())
                    .addValue("display", facet.display()).addValue("description", facet.description())
                    .addValue("parentName", facet.parentName()).addValue("facetCategoryName", facet.facetCategoryName());
                template.update(sql, params);
                facet.children().forEach(c -> c.setParentName(facet.name()));
            }
            currentTier = currentTier.stream().map(FacetDump::children).flatMap(List::stream).toList();
        }
        log.info("Done ingesting facets for {}", name);
    }

    public void addFacetCategoryMetasForSite(String name, List<FacetCategoryMetaDump> categoryMetas) {
        log.info("Starting facet category meta ingest for {}", name);
        String sql = """
            INSERT INTO facet_category_meta (FACET_CATEGORY_ID, KEY, VALUE)
            SELECT FACET_CATEGORY_ID, :key, :value
            FROM facet_category
            WHERE NAME = :facetCategoryName
            ON CONFLICT DO NOTHING
            """;
        for (FacetCategoryMetaDump meta : categoryMetas) {
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("key", meta.key()).addValue("value", meta.value())
                .addValue("facetCategoryName", meta.categoryName());
            template.update(sql, params);
        }
        log.info("Done ingesting facet category metas for {}", name);
    }

    public void addFacetMetasForSite(String name, List<FacetMetaDump> facetMetas) {
        log.info("Starting facet meta ingest for {}", name);
        String sql = """
            INSERT INTO facet_meta (FACET_ID, KEY, VALUE)
            SELECT FACET_ID, :key, :value
            FROM facet
                JOIN facet_category ON facet.FACET_CATEGORY_ID = facet_category.FACET_CATEGORY_ID
            WHERE facet.name = :facetName AND facet_category.name = :categoryName
            ON CONFLICT DO NOTHING
            """;
        for (FacetMetaDump meta : facetMetas) {
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("key", meta.key()).addValue("value", meta.value())
                .addValue("facetName", meta.facetName()).addValue("categoryName", meta.categoryName());
            template.update(sql, params);
        }
        log.info("Done ingesting facet metas for {}", name);
    }

    public void addConceptMetasForSite(String name, List<ConceptNodeMetaDump> conceptMetas) {
        log.info("Starting concept meta ingest for {}", name);
        String sql = """
            INSERT INTO concept_node_meta (CONCEPT_NODE_ID, KEY, VALUE)
            SELECT CONCEPT_NODE_ID, :key, :value
            FROM concept_node
            WHERE CONCEPT_PATH_MD5 = md5(:conceptPath::text)
            ON CONFLICT DO NOTHING
            """;
        MapSqlParameterSource[] params = conceptMetas.stream()
            .map(
                meta -> new MapSqlParameterSource().addValue("conceptPath", meta.conceptPath()).addValue("key", meta.key())
                    .addValue("value", meta.value())
            ).toArray(MapSqlParameterSource[]::new);
        template.batchUpdate(sql, params);
        log.info("Done ingesting concept metas for {}", name);
    }

    public void addFacetConceptPairsForSite(String name, List<FacetConceptPair> pairs) {
        log.info("Starting facet concept pair ingest for {}", name);
        String sql = """
            INSERT INTO facet__concept_node (FACET_ID, CONCEPT_NODE_ID)
            SELECT FACET_ID, CONCEPT_NODE_ID
            FROM facet
                JOIN facet_category ON facet.FACET_CATEGORY_ID = facet_category.FACET_CATEGORY_ID
                INNER JOIN concept_node ON concept_node.CONCEPT_PATH_MD5 = md5(:conceptPath::text)
            WHERE
                facet.NAME = :facetName
                AND facet_category.NAME = :categoryName
            ON CONFLICT DO NOTHING
            """;
        MapSqlParameterSource[] params = pairs.stream()
            .map(
                pair -> new MapSqlParameterSource().addValue("conceptPath", pair.conceptPath()).addValue("facetName", pair.facetName())
                    .addValue("categoryName", pair.facetCategory())
            ).toArray(MapSqlParameterSource[]::new);
        template.batchUpdate(sql, params);
        log.info("Done ingesting facet concept pairs for {}", name);
    }
}
