package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.entities.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;


@Testcontainers
@SpringBootTest
class RemoteDictionaryRepositoryInsertTest {

    @Autowired
    JdbcTemplate template;

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Autowired
    RemoteDictionaryRepository subject;

    @Test
    void shouldAddConceptHierarchy() {
        fixWeirdSeedBug();
        ConceptNodeDump top = new ConceptNodeDump("GIC-2", "GIC", "GIC", "internal", "\\GIC", null, null);
        ConceptNodeDump middle = new ConceptNodeDump("GIC-2", "concepts", "concepts", "internal", "\\GIC\\concepts", null, null);
        ConceptNodeDump middleB = new ConceptNodeDump("GIC-2", "seq", "seq", "internal", "\\GIC\\seq", null, null);
        ConceptNodeDump leafA1 = new ConceptNodeDump("GIC-2", "1", "1", "categorical", "\\GIC\\concepts\\1", null, null);
        ConceptNodeDump leafA2 = new ConceptNodeDump("GIC-2", "2", "2", "categorical", "\\GIC\\concepts\\2", null, null);
        ConceptNodeDump leafB1 = new ConceptNodeDump("GIC-2", "1", "1", "categorical", "\\GIC\\seq\\1", null, null);
        ConceptNodeDump leafB2 = new ConceptNodeDump("GIC-2", "2", "2", "categorical", "\\GIC\\seq\\2", null, null);
        top.addChild(middle);
        top.addChild(middleB);
        middle.addChild(leafA1);
        middle.addChild(leafA2);
        middleB.addChild(leafB1);
        middleB.addChild(leafB2);

        String countQ = """
            SELECT count(*)
            FROM concept_node
                JOIN dataset ON concept_node.DATASET_ID = dataset.DATASET_ID
            WHERE dataset.REF = 'GIC-2'
            """;
        Integer gicConcepts = template.queryForObject(countQ, Integer.class);
        Assertions.assertEquals(0, gicConcepts);

        subject.addConceptsForSite("bch", List.of(top));

        gicConcepts = template.queryForObject(countQ, Integer.class);
        Assertions.assertEquals(7, gicConcepts);
    }

    @Test
    void shouldAddFacets() {
        FacetDump child = new FacetDump("foo", "Foo", "fooer", "study_ids_dataset_ids", 1, null);
        FacetDump parent = new FacetDump("fooz", "Fooz", "fooerz", "study_ids_dataset_ids", List.of(child), 1, null);

        Integer before = template.queryForObject("SELECT count(*) FROM facet", Integer.class);
        subject.addFacetsForSite("bch", List.of(parent, parent));
        Integer after = template.queryForObject("SELECT count(*) FROM facet", Integer.class);
        Assertions.assertEquals(before + 2, after);
    }

    @Test
    void shouldAddFacetCategory() {
        FacetCategoryDump cat = new FacetCategoryDump("name", "display", "description");

        Integer before = template.queryForObject("SELECT count(*) FROM facet_category", Integer.class);
        subject.addFacetCategoriesForSite("bch", List.of(cat, cat));
        Integer after = template.queryForObject("SELECT count(*) FROM facet_category", Integer.class);
        Assertions.assertEquals(before + 1, after);
    }

    @Test
    void shouldAddFacetCategoryMeta() {
        FacetCategoryMetaDump meta = new FacetCategoryMetaDump("study_ids_dataset_ids", "key", "value");

        Integer before = template.queryForObject("SELECT count(*) FROM facet_category_meta", Integer.class);
        subject.addFacetCategoryMetasForSite("bch", List.of(meta, meta));
        Integer after = template.queryForObject("SELECT count(*) FROM facet_category_meta", Integer.class);
        Assertions.assertEquals(before + 1, after);
    }

    @Test
    void shouldAddFacetMeta() {
        FacetMetaDump meta = new FacetMetaDump("phs002715", "study_ids_dataset_ids", "key", "value");

        Integer before = template.queryForObject("SELECT count(*) FROM facet_meta", Integer.class);
        subject.addFacetMetasForSite("bch", List.of(meta, meta));
        Integer after = template.queryForObject("SELECT count(*) FROM facet_meta", Integer.class);
        Assertions.assertEquals(before + 1, after);
    }

    @Test
    void shouldPair() {
        FacetConceptPair pair = new FacetConceptPair("phs002715", "study_ids_dataset_ids", "\\Consent Type\\GIC Consent\\");

        Integer before = template.queryForObject("SELECT count(*) FROM facet__concept_node", Integer.class);
        subject.addFacetConceptPairsForSite("bch", List.of(pair, pair));
        Integer after = template.queryForObject("SELECT count(*) FROM facet__concept_node", Integer.class);
        Assertions.assertEquals(before + 1, after);
    }

    @Test
    void shouldAddConceptMetas() {
        fixWeirdSeedBug();
        ConceptNodeMetaDump pair = new ConceptNodeMetaDump("\\Consent Type\\GIC Consent\\", "key123123", "value");

        Integer before = template.queryForObject("SELECT count(*) FROM concept_node_meta", Integer.class);
        subject.addConceptMetasForSite("bch", List.of(pair, pair));
        Integer after = template.queryForObject("SELECT count(*) FROM concept_node_meta", Integer.class);
        Assertions.assertEquals(before + 1, after);
    }

    private void fixWeirdSeedBug() {
        List<String> queries = List.of(
            "INSERT INTO dataset (REF, FULL_NAME, ABBREVIATION) VALUES ('test', 'test', 'test')",
            "INSERT INTO concept_node (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH) VALUES (23, 'test', 'test', 'continuous', '\\a')",
            "INSERT INTO concept_node (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH) VALUES (23, 'test', 'test', 'continuous', '\\a')",
            "INSERT INTO concept_node_meta (CONCEPT_NODE_ID, KEY, VALUE) VALUES (216, 'K', 'V')",
            "INSERT INTO concept_node_meta (CONCEPT_NODE_ID, KEY, VALUE) VALUES (216, 'K', 'V')"
        );

        for (String query : queries) {
            try {
                template.update(query);
            } catch (Exception ignored) {
            }
        }
    }
}
