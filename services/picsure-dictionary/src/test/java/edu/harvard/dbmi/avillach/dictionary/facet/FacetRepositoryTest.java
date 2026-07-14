package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;


@Testcontainers
@SpringBootTest
class FacetRepositoryTest {

    @Autowired
    FacetRepository subject;

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
    void shouldGetAllFacets() {
        Filter filter = new Filter(List.of(), "", List.of());
        List<FacetCategory> actual = subject.getFacets(filter);
        List<FacetCategory> expected = List.of(
            new FacetCategory(
                "study_ids_dataset_ids", "Study IDs/Dataset IDs", "",
                List.of(
                    new Facet("1", "GIC", null, null, 13, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs000284", "CFS", null, "Chronic Fatigue Syndrome", 3, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs000007", "FHS", null, "Framingham Heart Study", 3, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs002385", "HCT_for_SCD", null, null, 3, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs002808", "nuMoM2b", null, null, 3, List.of(), "study_ids_dataset_ids", null),
                    new Facet(
                        "2", "National Health and Nutrition Examination Survey", null, null, 2, List.of(), "study_ids_dataset_ids", null
                    ),
                    new Facet(
                        "phs002715", "NSRR CFS", null, "National Sleep Research Resource", 2, List.of(), "study_ids_dataset_ids", null
                    ), new Facet("3", "1000 Genomes Project", null, null, 0, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs003463", "RECOVER_Adult", null, null, 0, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs003543", "NSRR_HSHC", null, null, 0, List.of(), "study_ids_dataset_ids", null),
                    new Facet("phs003566", "SPRINT", null, null, 0, List.of(), "study_ids_dataset_ids", null),
                    new Facet(
                        "phs001963", "DEMENTIA-SEQ", null, null, 0,
                        List.of(
                            new Facet("NEST_1", "My Nested Facet 1", null, null, 0, List.of(), "study_ids_dataset_ids", null),
                            new Facet("NEST_2", "My Nested Facet 2", null, null, 0, List.of(), "study_ids_dataset_ids", null)
                        ), "study_ids_dataset_ids", null
                    )
                )
            ),
            new FacetCategory(
                "nsrr_harmonized", "Common Data Element Collection", "",
                List.of(
                    new Facet("PhenX", "PhenX", null, null, 2, List.of(), "nsrr_harmonized", null),
                    new Facet("LOINC", "LOINC", null, null, 1, List.of(), "nsrr_harmonized", null),
                    new Facet(
                        "gad_7", "Generalized Anxiety Disorder Assessment (GAD-7)", null, null, 0, List.of(), "nsrr_harmonized", null
                    ),
                    new Facet("taps_tool", "NIDA CTN Common Data Elements = TAPS Tool", null, null, 0, List.of(), "nsrr_harmonized", null)
                )
            )
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacetWithChildren() {
        Optional<Facet> actual = subject.getFacet("study_ids_dataset_ids", "phs001963");
        Facet expected = new Facet(
            "phs001963", "DEMENTIA-SEQ", null, null, null,
            List.of(
                new Facet("NEST_1", "My Nested Facet 1", null, null, null, List.of(), "study_ids_dataset_ids", null),
                new Facet("NEST_2", "My Nested Facet 2", null, null, null, List.of(), "study_ids_dataset_ids", null)
            ), "study_ids_dataset_ids", null
        );

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }

    @Test
    void shouldGetFacet() {
        Optional<Facet> actual = subject.getFacet("study_ids_dataset_ids", "phs000007");
        Optional<Facet> expected =
            Optional.of(new Facet("phs000007", "FHS", null, "Framingham Heart Study", null, List.of(), "study_ids_dataset_ids", null));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotGetFacetThatDNE() {
        Optional<Facet> actual = subject.getFacet("site", "Kansas");
        Optional<Facet> expected = Optional.empty();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacetMeta() {
        Map<String, String> actual = subject.getFacetMeta("study_ids_dataset_ids", "phs000007");
        Map<String, String> expected = Map.of("full_name", "Framingham Heart Study");

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetEmptyMeta() {
        Map<String, String> actual = subject.getFacetMeta("bad category", "bad facet");
        Map<String, String> expected = Map.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetFacetCategoryOrder() {
        Map<String, String> actual = subject.getFacetCategoryOrder(List.of("study_ids_dataset_ids", "nsrr_harmonized"));
        Map<String, String> expected = Map.of("study_ids_dataset_ids", "1");

        Assertions.assertEquals(expected, actual);
    }
}
