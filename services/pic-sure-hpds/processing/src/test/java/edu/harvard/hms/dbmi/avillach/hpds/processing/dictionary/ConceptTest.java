package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ConceptTest {

    @Test
    public void jsonSerialization() throws JsonProcessingException {
        Concept[] concepts = new Concept[]{new Concept("\\demographics\\age\\", "age", "AGE", null, "patient age", Map.of("drs_uri", "[\"a-drs.uri\", \"another-drs.uri\"]"))};
        ObjectMapper objectMapper = new ObjectMapper();

        String serialized = objectMapper.writeValueAsString(concepts);
        Concept[] deserialized = objectMapper.readValue(serialized, Concept[].class);

        assertEquals(List.of(concepts), List.of(deserialized));
    }
}