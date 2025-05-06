package edu.harvard.dbmi.avillach.dump.local;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeDump;
import edu.harvard.dbmi.avillach.dump.entities.DumpRow;
import edu.harvard.dbmi.avillach.dump.entities.FacetCategoryDump;
import edu.harvard.dbmi.avillach.dump.entities.FacetDump;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDateTime;
import java.util.List;

@Testcontainers
@SpringBootTest
class DumpRepositoryTest {

    @Autowired
    DumpRepository subject;

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

    @Test
    void shouldDumpFacets() {
        List<ConceptNodeDump> concepts = subject.getAllConcepts();
        // very lazy test. The logic for recursing through the dump would have been way too much for a test
        Assertions.assertNotNull(concepts);
    }

    @Test
    void shouldGetLastUpdatedTime() {
        LocalDateTime actual = subject.getLastUpdated();
        LocalDateTime expected = LocalDateTime.of(2020, 2, 2, 0, 0, 0);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetAllConceptNodeMetas() {
        List<? extends DumpRow> actual = subject.getAllConceptNodeMetas();

        Assertions.assertEquals(117, actual.size());
    }

    @Test
    void shouldGetAllFacets() {
        List<FacetDump> actual = (List<FacetDump>) subject.getAllFacets();
        long actualChildCount = actual.stream().map(FacetDump::children).flatMap(List::stream).count();
        Assertions.assertEquals(16, actual.size());
        Assertions.assertEquals(2, actualChildCount);
    }

    @Test
    void shouldGetAllFacetMetas() {
        List<? extends DumpRow> actual = subject.getAllFacetMetas();
        Assertions.assertEquals(3, actual.size());
    }

    @Test
    void shouldGetAllFacetCategories() {
        List<? extends DumpRow> actual = subject.getAllFacetCategories();

        List<FacetCategoryDump> expected = List.of(
            new FacetCategoryDump("study_ids_dataset_ids", "Study IDs/Dataset IDs", ""),
            new FacetCategoryDump("nsrr_harmonized", "Common Data Element Collection", ""),
            new FacetCategoryDump("cde", "NSRR Harmonized", "")
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetAllFacetCategoryMetas() {
        List<? extends DumpRow> actual = subject.getAllFacetCategoryMetas();
        Assertions.assertEquals(1, actual.size());
    }

    @Test
    void shouldGetAllFacetConceptPairs() {
        List<? extends DumpRow> actual = subject.getAllFacetConceptPairs();
        Assertions.assertEquals(94, actual.size());
    }
}
