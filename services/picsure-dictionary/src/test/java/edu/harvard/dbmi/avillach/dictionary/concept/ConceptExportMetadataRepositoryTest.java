package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptExportMetadata;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;

/**
 * Runs {@link ConceptRepository#getExportMetadata} against the seeded PostgreSQL container with a no-show list that hides {@code drs_uri},
 * {@code stigmatized} and {@code description}, the way a production deployment does, and proves the export query ignores that list, keeps
 * metadata keys as stored, and agrees with the bulk concept detail query on the columns both return.
 */
@Testcontainers
@SpringBootTest(properties = "metadata.no_show_list=values,stigmatized,drs_uri,description")
class ConceptExportMetadataRepositoryTest {

    private static final String COLA_PATH = "\\phs000007\\pht000021\\phv00003844\\FL200\\";
    private static final String AGE_PATH = "\\phs000284\\pht001902\\phv00122507\\age\\";
    private static final String UNKNOWN_PATH = "\\phs000007\\pht000021\\not_a_concept\\";
    private static final int DRS_URI_ROW_ID = 900_001;
    private static final String DRS_URI_JSON = "[\"drs://dg.4503:dg.4503/aaa\", \"drs://dg.4503:dg.4503/bbb\"]";
    private static final List<String> DRS_URI_ONLY = List.of("drs_uri");

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Autowired
    ConceptRepository subject;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private static String description(Concept concept) {
        return switch (concept) {
            case CategoricalConcept categorical -> categorical.description();
            case ContinuousConcept continuous -> continuous.description();
            case ConceptShell shell -> throw new IllegalStateException("Concept shell returned for " + shell.conceptPath());
        };
    }

    @BeforeEach
    void addDrsUriRow() {
        jdbcTemplate.update(
            "INSERT INTO concept_node_meta (concept_node_meta_id, concept_node_id, key, value) OVERRIDING SYSTEM VALUE "
                + "SELECT ?, concept_node_id, 'drs_uri', ? FROM concept_node WHERE concept_path = ?",
            DRS_URI_ROW_ID, DRS_URI_JSON, COLA_PATH
        );
    }

    @AfterEach
    void removeDrsUriRow() {
        jdbcTemplate.update("DELETE FROM concept_node_meta WHERE concept_node_meta_id = ?", DRS_URI_ROW_ID);
    }

    @Test
    void returnsTheRawDrsUriKeyAndItsJsonTextDespiteTheNoShowList() {
        List<ConceptExportMetadata> actual = subject.getExportMetadata(List.of(COLA_PATH), DRS_URI_ONLY);

        assertEquals(
            List.of(
                new ConceptExportMetadata(
                    COLA_PATH, "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", Map.of("drs_uri", DRS_URI_JSON)
                )
            ), actual
        );
    }

    @Test
    void leavesOutMetaKeysThatWereNotRequested() {
        ConceptExportMetadata actual = subject.getExportMetadata(List.of(COLA_PATH), DRS_URI_ONLY).getFirst();

        assertEquals(List.of("drs_uri"), List.copyOf(actual.meta().keySet()));
    }

    @Test
    void returnsStigmatizedOnlyWhenRequested() {
        ConceptExportMetadata actual = subject.getExportMetadata(List.of(COLA_PATH), List.of("drs_uri", "stigmatized")).getFirst();

        assertEquals(Map.of("drs_uri", DRS_URI_JSON, "stigmatized", "false"), actual.meta());
    }

    @Test
    void returnsAnEmptyMetaMapForAConceptWithoutTheRequestedKeys() {
        ConceptExportMetadata actual = subject.getExportMetadata(List.of(AGE_PATH), DRS_URI_ONLY).getFirst();

        assertEquals(AGE_PATH, actual.conceptPath());
        assertEquals(Map.of(), actual.meta());
    }

    @Test
    void returnsAnEmptyMetaMapWhenNoKeysAreRequested() {
        ConceptExportMetadata actual = subject.getExportMetadata(List.of(COLA_PATH), List.of()).getFirst();

        assertEquals("FL200", actual.display());
        assertEquals(Map.of(), actual.meta());
    }

    @Test
    void omitsUnknownPaths() {
        List<ConceptExportMetadata> actual = subject.getExportMetadata(List.of(UNKNOWN_PATH, COLA_PATH), DRS_URI_ONLY);

        assertEquals(List.of(COLA_PATH), actual.stream().map(ConceptExportMetadata::conceptPath).toList());
    }

    @Test
    void returnsOneRowForAPathRequestedTwice() {
        List<ConceptExportMetadata> actual = subject.getExportMetadata(List.of(COLA_PATH, COLA_PATH), DRS_URI_ONLY);

        assertEquals(1, actual.size());
        assertEquals(Map.of("drs_uri", DRS_URI_JSON), actual.getFirst().meta());
    }

    @Test
    void acceptsAThousandPathsInOneCall() {
        List<String> paths = new ArrayList<>(IntStream.range(0, 998).mapToObj(index -> "\\phs000007\\missing" + index + "\\").toList());
        paths.add(COLA_PATH);
        paths.add(AGE_PATH);

        List<ConceptExportMetadata> actual = subject.getExportMetadata(paths, DRS_URI_ONLY);

        assertEquals(List.of(COLA_PATH, AGE_PATH), actual.stream().map(ConceptExportMetadata::conceptPath).toList());
    }

    @Test
    void agreesWithTheBulkConceptDetailQueryOnDisplayDatasetAndDescription() {
        List<String> paths = List.of(COLA_PATH, AGE_PATH, "\\phs002385\\TXNUM\\", "\\ACT Lab Test Results\\");

        List<ConceptExportMetadata> actual = subject.getExportMetadata(paths, DRS_URI_ONLY);
        Map<String, Concept> detail =
            subject.getConceptsByPathWithMetadata(paths).stream().collect(Collectors.toMap(Concept::conceptPath, c -> c));

        assertEquals(detail.keySet(), actual.stream().map(ConceptExportMetadata::conceptPath).collect(Collectors.toSet()));
        assertEquals(paths.size(), actual.size());
        for (ConceptExportMetadata row : actual) {
            Concept expected = detail.get(row.conceptPath());
            assertEquals(expected.display(), row.display(), row.conceptPath());
            assertEquals(expected.dataset(), row.dataset(), row.conceptPath());
            assertEquals(description(expected), row.description(), row.conceptPath());
        }
        assertTrue(actual.stream().anyMatch(row -> row.description() != null));
    }
}
