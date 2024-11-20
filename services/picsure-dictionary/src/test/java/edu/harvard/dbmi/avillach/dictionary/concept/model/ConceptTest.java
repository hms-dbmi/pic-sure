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
        Concept expected =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "study_a", null, List.of("a", "b"), true, "", List.of(), Map.of());
        String json = objectMapper.writeValueAsString(expected);
        Concept actual = objectMapper.readValue(json, Concept.class);

        Assertions.assertEquals(expected, actual);
        Assertions.assertEquals(ConceptType.Categorical, actual.type());
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

        CategoricalConcept expected =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "study_a", null, List.of("a", "b"), true, "", null, Map.of());
        Concept actual = new ObjectMapper().readValue(json, Concept.class);

        Assertions.assertEquals(expected, actual);
        Assertions.assertEquals(ConceptType.Categorical, actual.type());
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

        ContinuousConcept expected = new ContinuousConcept("/foo//baz", "baz", "Baz", "study_a", null, true, 0F, 1F, "", Map.of());
        Concept actual = new ObjectMapper().readValue(json, Concept.class);

        Assertions.assertEquals(expected, actual);
        Assertions.assertEquals(ConceptType.Continuous, actual.type());
    }

    @Test
    void shouldIncludeTypeInList() throws JsonProcessingException {
        List<Record> concepts = List.of(
            new ContinuousConcept("/foo//baz", "baz", "Baz", "study_a", null, true, 0F, 1F, "", Map.of()),
            new CategoricalConcept("/foo//bar", "bar", "Bar", "study_a", null, List.of("a", "b"), true, "", null, Map.of())
        );

        String actual = new ObjectMapper().writeValueAsString(concepts);
        String expected =
            "[{\"conceptPath\":\"/foo//baz\",\"name\":\"baz\",\"display\":\"Baz\",\"dataset\":\"study_a\",\"description\":null,\"allowFiltering\":true,\"min\":0.0,\"max\":1.0,\"studyAcronym\":\"\",\"meta\":{},\"children\":null,\"table\":null,\"study\":null},{\"conceptPath\":\"/foo//bar\",\"name\":\"bar\",\"display\":\"Bar\",\"dataset\":\"study_a\",\"description\":null,\"values\":[\"a\",\"b\"],\"allowFiltering\":true,\"studyAcronym\":\"\",\"children\":null,\"meta\":{},\"table\":null,\"study\":null}]";
        Assertions.assertEquals(expected, actual);
    }
}
