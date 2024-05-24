package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ConceptTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRoundTrip() throws JsonProcessingException {
        Concept expected = new CategoricalConcept("/foo//bar", "bar", "Bar", "study_a", List.of("a", "b"), List.of(), Map.of());
        String json = objectMapper.writeValueAsString(expected);
        Concept actual = objectMapper.readValue(json, Concept.class);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReadCategorical() throws JsonProcessingException {
        String json = """
            {
              "type" : "Categorical",
              "conceptPath" : "/foo//bar",
              "name" : "bar",
              "display" : "Bar",
              "dataset" : "study_a",
              "values" : [ "a", "b" ],
              "parent" : null,
              "meta" : { }
            }
            """;

        CategoricalConcept expected = new CategoricalConcept("/foo//bar", "bar", "Bar", "study_a", List.of("a", "b"), null, Map.of());
        Concept actual = new ObjectMapper().readValue(json, Concept.class);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldReadContinuous() throws JsonProcessingException {
        String json = """
            {
              "type" : "Continuous",
              "conceptPath" : "/foo//baz",
              "name" : "baz",
              "display" : "Baz",
              "dataset" : "study_a",
              "parent" : null,
              "min" : 0,
              "max" : 1,
              "meta" : { }
            }
            """;

        ContinuousConcept expected = new ContinuousConcept("/foo//baz", "baz", "Baz", "study_a", 0, 1, Map.of());
        Concept actual = new ObjectMapper().readValue(json, Concept.class);

        Assertions.assertEquals(expected, actual);
    }
}