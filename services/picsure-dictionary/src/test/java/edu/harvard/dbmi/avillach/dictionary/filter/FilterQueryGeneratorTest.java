package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;

@SpringBootTest
class FilterQueryGeneratorTest {

    @Autowired
    FilterQueryGenerator subject;

    @Test
    void shouldGenerateEmptyQuery() {
        Filter filter = new Filter(List.of(), "");

        String expectedSQL = """
            SELECT DISTINCT concept_node.concept_node_id
                FROM concept_node
                    LEFT JOIN facet__concept_node ON facet__concept_node.concept_node_id = concept_node.concept_node_id
                    LEFT JOIN facet ON facet.facet_id = facet__concept_node.facet_id
                    LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                WHERE TRUE
            ORDER BY concept_node.concept_node_id
            """;
        QueryParamPair actual = subject.generateFilterQuery(filter);
        QueryParamPair expected = new QueryParamPair(expectedSQL, new MapSqlParameterSource());

        Assertions.assertEquals(expected.query().trim(), actual.query().trim());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        Assertions.assertEquals(expected.params().getValues(), actual.params().getValues());
    }

    @Test
    void shouldGenerateEmptyQueryForNulls() {
        Filter filter = new Filter(null, null);

        String expectedSQL = """
            SELECT DISTINCT concept_node.concept_node_id
                FROM concept_node
                    LEFT JOIN facet__concept_node ON facet__concept_node.concept_node_id = concept_node.concept_node_id
                    LEFT JOIN facet ON facet.facet_id = facet__concept_node.facet_id
                    LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                WHERE TRUE
            ORDER BY concept_node.concept_node_id
            """;
        QueryParamPair actual = subject.generateFilterQuery(filter);
        QueryParamPair expected = new QueryParamPair(expectedSQL, new MapSqlParameterSource());

        Assertions.assertEquals(expected.query().trim(), actual.query().trim());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        Assertions.assertEquals(expected.params().getValues(), actual.params().getValues());
    }

    @Test
    void shouldGenerateQueryForFacet() {
        Filter filter = new Filter(List.of(new Facet("name", "", "", null, "cat")), null);

        String expectedSQL = """
            SELECT DISTINCT concept_node.concept_node_id
                FROM concept_node
                    LEFT JOIN facet__concept_node ON facet__concept_node.concept_node_id = concept_node.concept_node_id
                    LEFT JOIN facet ON facet.facet_id = facet__concept_node.facet_id
                    LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                WHERE TRUE
            AND (facet.name, facet_category.name) IN (:facets)
            ORDER BY concept_node.concept_node_id
            """;
        QueryParamPair actual = subject.generateFilterQuery(filter);
        QueryParamPair expected =
            new QueryParamPair(expectedSQL, new MapSqlParameterSource().addValue("facets", List.of(new Object[]{"name", "cat"})));

        Assertions.assertEquals(expected.query().trim(), actual.query().trim());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        // Also, Object comparison is rough, so just looking at size
        Assertions.assertEquals(expected.params().getValues().size(), actual.params().getValues().size());
    }

    @Test
    void shouldGenerateQueryForSearch() {
        Filter filter = new Filter(null, "arthritis");

        String expectedSQL = """
            SELECT DISTINCT concept_node.concept_node_id
                FROM concept_node
                    LEFT JOIN facet__concept_node ON facet__concept_node.concept_node_id = concept_node.concept_node_id
                    LEFT JOIN facet ON facet.facet_id = facet__concept_node.facet_id
                    LEFT JOIN facet_category ON facet_category.facet_category_id = facet.facet_category_id
                WHERE TRUE
            AND concept_node.concept_path LIKE :search
            ORDER BY concept_node.concept_node_id
            """;
        QueryParamPair actual = subject.generateFilterQuery(filter);
        QueryParamPair expected =
            new QueryParamPair(expectedSQL, new MapSqlParameterSource().addValue("search", "%arthritis%"));

        Assertions.assertEquals(expected.query().trim(), actual.query().trim());
        // MapSqlParameterSource has a bad equals() so we look at the internals
        // Also, Object comparison is rough, so just looking at size
        Assertions.assertEquals(expected.params().getValues(), actual.params().getValues());
    }
}