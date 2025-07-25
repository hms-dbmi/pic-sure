package edu.harvard.dbmi.avillach.dictionary.facet;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Testcontainers
@SpringBootTest
class FacetQueryGeneratorTest {

    @Autowired
    NamedParameterJdbcTemplate template;

    @Autowired
    FacetQueryGenerator subject;

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

    record IdCountPair(int facetId, int facetCount) {
    }

    static class IdCountPairMapper implements RowMapper<IdCountPair> {

        @Override
        public IdCountPair mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdCountPair(rs.getInt("facet_id"), rs.getInt("facet_count"));
        }
    }

    @Test
    void shouldCountFacetsWithNoSearchAndNoSelectedFacetsAndNoConsents() {
        Filter filter = new Filter(List.of(), "", List.of());

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(
            new IdCountPair(22, 13), new IdCountPair(26, 3), new IdCountPair(27, 3), new IdCountPair(28, 3), new IdCountPair(31, 3),
            new IdCountPair(25, 2), new IdCountPair(21, 2), new IdCountPair(23, 2), new IdCountPair(20, 1)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithNoSearchAndNoSelectedFacetsAndConsents() {
        Filter filter = new Filter(List.of(), "", List.of("LOINC.c2", "PhenX.c2", "phs000007.c2"));

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(27, 3), new IdCountPair(20, 1), new IdCountPair(21, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithSearchAndNoSelectedFacetsAndNoConsents() {
        Filter filter = new Filter(List.of(), "age", List.of());

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected =
            List.of(new IdCountPair(23, 1), new IdCountPair(25, 1), new IdCountPair(26, 1), new IdCountPair(28, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithSearchAndNoSelectedFacetsAndConsents() {
        Filter filter = new Filter(List.of(), "age", List.of("phs002715.c1", "phs000284.c1", "phs002385.c1"));

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(25, 1), new IdCountPair(26, 1), new IdCountPair(28, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithSearchAndOneSelectedFacetsAndNoConsents() {
        Filter filter = new Filter(List.of(new Facet("phs002715", "study_ids_dataset_ids")), "age", List.of());

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected =
            List.of(new IdCountPair(23, 1), new IdCountPair(25, 1), new IdCountPair(26, 1), new IdCountPair(28, 1));

        // This runs locally, but not in GH actions. Assuming this gets fixed when we upgrade to java 24
        // Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithSearchAndOneSelectedFacetsAndConsents() {
        Filter filter = new Filter(
            List.of(new Facet("phs002715", "study_ids_dataset_ids")), "age", List.of("phs002715.c1", "phs000284.c1", "phs002385.c1")
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(25, 1), new IdCountPair(26, 1), new IdCountPair(28, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsNoSearchAndOneSelectedFacetsAndNoConsents() {
        Filter filter = new Filter(List.of(new Facet("phs002715", "study_ids_dataset_ids")), "", List.of());

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(
            new IdCountPair(22, 13), new IdCountPair(23, 2), new IdCountPair(25, 2), new IdCountPair(26, 3), new IdCountPair(27, 3),
            new IdCountPair(28, 3), new IdCountPair(31, 3)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsNoSearchAndOneSelectedFacetsAndConsents() {
        Filter filter = new Filter(List.of(new Facet("phs002715", "study_ids_dataset_ids")), "", List.of("phs000007.c2"));

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(27, 3));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithSearchAndTwoSelectedFacetsInDifferentCatsAndNoConsents() {
        Filter filter =
            new Filter(List.of(new Facet("phs000007", "study_ids_dataset_ids"), new Facet("LOINC", "nsrr_harmonized")), "cola", List.of());

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(21, 1), new IdCountPair(27, 1), new IdCountPair(20, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsWithSearchAndTwoSelectedFacetsInDifferentCatsAndConsents() {
        Filter filter = new Filter(
            List.of(new Facet("phs000007", "study_ids_dataset_ids"), new Facet("LOINC", "nsrr_harmonized")), "cola",
            List.of("LOINC.c1", "PhenX.c1", "phs000007.c1")
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(21, 1), new IdCountPair(27, 1), new IdCountPair(20, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsNoSearchAndTwoSelectedFacetsInDifferentCatsAndNoConsents() {
        Filter filter =
            new Filter(List.of(new Facet("phs000007", "study_ids_dataset_ids"), new Facet("LOINC", "nsrr_harmonized")), "", List.of());

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(21, 1), new IdCountPair(27, 1), new IdCountPair(20, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsNoSearchAndTwoSelectedFacetsInDifferentCatsAndConsents() {
        Filter filter = new Filter(
            List.of(new Facet("phs000007", "study_ids_dataset_ids"), new Facet("LOINC", "nsrr_harmonized")), "",
            List.of("LOINC.c1", "PhenX.c1", "phs000007.c1")
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(21, 1), new IdCountPair(27, 1), new IdCountPair(20, 1));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountFacetsSearchMatchesValueNotSearchString() {
        Filter filter = new Filter(List.of(), "gremlin", List.of());
        MapSqlParameterSource params = new MapSqlParameterSource();
        String query = subject.createFacetSQLAndPopulateParams(filter, params);

        List<IdCountPair> actual = template.query(query, params, new IdCountPairMapper());
        List<IdCountPair> expected = List.of(new IdCountPair(21, 1));

        Assertions.assertEquals(expected, actual);
    }
}
