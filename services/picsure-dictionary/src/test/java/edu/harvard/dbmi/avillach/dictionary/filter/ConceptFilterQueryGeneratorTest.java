package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptFilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;

@Testcontainers
@SpringBootTest
class ConceptFilterQueryGeneratorTest {

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
    ConceptFilterQueryGenerator subject;

    @Autowired
    NamedParameterJdbcTemplate template;

    @Test
    void shouldPutStigvarsLastForEmptySearch() {
        Filter filter = new Filter(List.of(), "", List.of());
        QueryParamPair pair = subject.generateFilterQuery(filter, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);

        Assertions.assertEquals(246, actual.getLast());
    }

    @Test
    void shouldGenerateForHarmonizedConsents() {
        Filter filter = new Filter(List.of(), "", List.of("phs001963.c1"));
        QueryParamPair pair = subject.generateFilterQuery(filter, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of(270);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForFacetAndSearchNoMatch() {
        Filter f =
            new Filter(List.of(new Facet("phs000007", "FHS", "", "", null, null, "study_ids_dataset_ids", null)), "smoke", List.of());
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForFHSFacet() {
        Filter f = new Filter(List.of(new Facet("phs000007", "FHS", "", "", null, null, "study_ids_dataset_ids", null)), "", List.of());
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of(229, 232, 235);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForFHSFacetWithConsent1() {
        Filter f = new Filter(
            List.of(new Facet("phs000007", "FHS", "", "", null, null, "study_ids_dataset_ids", null)), "", List.of("phs000007.c1")
        );
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of(229, 232, 235);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForFHSFacetWithConsent1And2() {
        Filter f = new Filter(
            List.of(new Facet("phs000007", "FHS", "", "", null, null, "study_ids_dataset_ids", null)), "",
            List.of("phs000007.c1", "phs000007.c2")
        );
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of(229, 232, 235);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForFHSFacetWithConsent3() {
        Filter f =
            new Filter(List.of(new Facet("phs000007", "FHS", "", "", null, null, "study_ids_dataset_ids", null)), "", List.of("dne.c3"));
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForFacetAndSearchMatch() {
        Filter f =
            new Filter(List.of(new Facet("phs002715", "NSRR", "", "", null, null, "study_ids_dataset_ids", null)), "smoke", List.of());
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of(249);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGenerateForNSRRFacet() {
        Filter f = new Filter(List.of(new Facet("phs002715", "NSRR", "", "", null, null, "study_ids_dataset_ids", null)), "", List.of());
        QueryParamPair pair = subject.generateFilterQuery(f, Pageable.unpaged());
        String query = "WITH " + pair.query() + "\n SELECT concept_node_id FROM concepts_filtered_sorted;";

        List<Integer> actual = template.queryForList(query, pair.params(), Integer.class);
        List<Integer> expected = List.of(248, 249);

        Assertions.assertEquals(expected, actual);
    }
}
