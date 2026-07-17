package edu.harvard.dbmi.avillach.domain;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PaginatedSearchResultTest {

    @Test
    public void testJacksonSerialization() throws JsonProcessingException {
        PaginatedSearchResult<String> paginatedSearchResult = new PaginatedSearchResult<>(List.of("a", "b", "c"), 1, 3);
        ObjectMapper objectMapper = new ObjectMapper();
        String serialized = objectMapper.writeValueAsString(paginatedSearchResult);
        PaginatedSearchResult<String> deserialized = objectMapper.readValue(serialized, PaginatedSearchResult.class);
        assertEquals(paginatedSearchResult, deserialized);
    }
}
