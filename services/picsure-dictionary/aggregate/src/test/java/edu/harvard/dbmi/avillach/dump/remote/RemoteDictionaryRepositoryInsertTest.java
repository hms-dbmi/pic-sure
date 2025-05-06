package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeDump;
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

    private void fixWeirdSeedBug() {
        List<String> queries = List.of(
            "INSERT INTO dataset (REF, FULL_NAME, ABBREVIATION) VALUES ('test', 'test', 'test')",
            "INSERT INTO concept_node (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH) VALUES (23, 'test', 'test', 'continuous', '\\a')",
            "INSERT INTO concept_node (DATASET_ID, NAME, DISPLAY, CONCEPT_TYPE, CONCEPT_PATH) VALUES (23, 'test', 'test', 'continuous', '\\a')"
        );

        for (String query : queries) {
            try {
                template.update(query);
            } catch (Exception ignored) {
            }
        }
    }
}
