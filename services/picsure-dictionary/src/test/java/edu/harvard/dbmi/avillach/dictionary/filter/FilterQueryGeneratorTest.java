package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.util.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;

import static edu.harvard.dbmi.avillach.dictionary.filter.FilterQueryGenerator.FACET_QUERY;
import static edu.harvard.dbmi.avillach.dictionary.filter.FilterQueryGenerator.SEARCH_QUERY;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FilterQueryGeneratorTest {

    @Autowired
    FilterQueryGenerator subject;

    @Test
    void shouldGenerateEmptyQuery() {
        Filter filter = new Filter(List.of(), "");

        Pair<String, MapSqlParameterSource> actual = subject.generateFilterQuery(filter);
        Pair<String, MapSqlParameterSource> expected = new Pair<>("", new MapSqlParameterSource());

        Assertions.assertEquals(expected.first(), actual.first());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        Assertions.assertEquals(expected.second().getValues(), actual.second().getValues());
    }

    @Test
    void shouldGenerateEmptyQueryForNulls() {
        Filter filter = new Filter(null, null);

        Pair<String, MapSqlParameterSource> actual = subject.generateFilterQuery(filter);
        Pair<String, MapSqlParameterSource> expected = new Pair<>("", new MapSqlParameterSource());

        Assertions.assertEquals(expected.first(), actual.first());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        Assertions.assertEquals(expected.second().getValues(), actual.second().getValues());
    }

    @Test
    void shouldGenerateQueryForFacet() {
        Filter filter = new Filter(List.of(new Facet("name", "", "", null, "cat")), null);

        Pair<String, MapSqlParameterSource> actual = subject.generateFilterQuery(filter);
        Pair<String, MapSqlParameterSource> expected =
            new Pair<>(FACET_QUERY, new MapSqlParameterSource().addValue("facets", List.of(new Object[]{"name", "cat"})));

        Assertions.assertEquals(expected.first(), actual.first());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        // Also, Object comparison is rough, so just looking at size
        Assertions.assertEquals(expected.second().getValues().size(), actual.second().getValues().size());
    }

    @Test
    void shouldGenerateQueryForSearch() {
        Filter filter = new Filter(null, "arthritis");

        Pair<String, MapSqlParameterSource> actual = subject.generateFilterQuery(filter);
        Pair<String, MapSqlParameterSource> expected =
            new Pair<>(SEARCH_QUERY, new MapSqlParameterSource().addValue("search", "%arthritis%"));

        Assertions.assertEquals(expected.first(), actual.first());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        // Also, Object comparison is rough, so just looking at size
        Assertions.assertEquals(expected.second().getValues(), actual.second().getValues());
    }
}