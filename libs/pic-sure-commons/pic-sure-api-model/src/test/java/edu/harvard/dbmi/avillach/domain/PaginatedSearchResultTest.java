package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

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
