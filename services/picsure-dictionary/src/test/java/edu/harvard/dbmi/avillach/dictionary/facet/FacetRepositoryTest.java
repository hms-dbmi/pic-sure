package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptRepository;
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
        static final PostgreSQLContainer<?> databaseContainer =
            new PostgreSQLContainer<>("postgres:16")
                .withReuse(true)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql"
                );

        @DynamicPropertySource
        static void mySQLProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
            registry.add("spring.datasource.username", databaseContainer::getUsername);
            registry.add("spring.datasource.password", databaseContainer::getPassword);
            registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
        }

    @Test
    void shouldGetAllFacets() {
        Filter filter = new Filter(List.of(), "");

        List<FacetCategory> actual = subject.getFacets(filter);

        Assertions.assertEquals(2, actual.size());
    }

    @Test
    void shouldFilterFacetsBySearch() {
        Filter filter = new Filter(List.of(), "X");
        List<FacetCategory> actual = subject.getFacets(filter);

        List<FacetCategory> expected = List.of(
            new FacetCategory(
                "site", "Site", "Filter variables by site",
                List.of(
                    new Facet("bch", "BCH", "Boston Childrens Hospital", "Boston Childrens Hospital", 1, null, "category", null)
                )
            ),
            new FacetCategory(
                "data_source", "Data Source", "What does this data relate to (image, questionnaire...)",
                List.of(
                    new Facet("imaging", "Imaging", "Data derived from an image", "Data derived from an image", 1, null, "data_source", null),
                    new Facet("questionnaire", "questionnaire", "Data derived from a questionnaire", "Data derived from a questionnaire", 1, null, "data_source", null),
                    new Facet("lab_test", "Lab Test", "Data derived from a lab test", "Data derived from a lab test", 1, null, "data_source", null)
                )
            )
        );
    }

    @Test
    void shouldFilterFacetsByFacet() {
        Filter filter = new Filter(List.of(new Facet("bch", "BCH", "Boston Childrens Hospital", "Boston Childrens Hospital", 1, null, "category", null)), "");
        List<FacetCategory> actual = subject.getFacets(filter);

        List<FacetCategory> expected = List.of(
            new FacetCategory(
                "site", "Site", "Filter variables by site",
                List.of(
                    new Facet("bch", "BCH", "Boston Childrens Hospital", "Boston Childrens Hospital", 1, null, "category", null),
                    new Facet("narnia", "Narnia", "Narnia", "Narnia", 1, null, "category", null)
                )
            ),
            new FacetCategory(
                "data_source", "Data Source", "What does this data relate to (image, questionnaire...)",
                List.of(
                    new Facet("imaging", "Imaging", "Data derived from an image", "Data derived from an image", 1, null, "data_source", null),
                    new Facet("questionnaire", "questionnaire", "Data derived from a questionnaire", "Data derived from a questionnaire", 1, null, "data_source", null),
                    new Facet("lab_test", "Lab Test", "Data derived from a lab test", "Data derived from a lab test", 1, null, "data_source", null)
                )
            )
        );
    }

    @Test
    void shouldGetFacet() {
        Optional<Facet> actual = subject.getFacet("study_ids_dataset_ids", "phs000007");
        Optional<Facet> expected = Optional.of(new Facet("phs000007", "FHS", null, "Framingham Heart Study", null, null, "study_ids_dataset_ids", null));

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
}