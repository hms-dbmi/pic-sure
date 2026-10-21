package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

public class StrictQueryDeserializerTest {

    private static final ObjectMapper LENIENT = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static final String V1_QUERY = "{\"expectedResultType\":\"COUNT\",\"fields\":[\"\\\\demographics\\\\AGE\\\\\"],"
        + "\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"min\":40}}}";

    record Envelope(@JsonDeserialize(using = StrictQueryDeserializer.class) Query query) {
    }

    @Test
    public void v1Query_lenientMapper_isRejectedThroughTheDeserializer() {
        JsonMappingException rejected =
            assertThrows(JsonMappingException.class, () -> LENIENT.readValue("{\"query\":" + V1_QUERY + "}", Envelope.class));

        assertTrue(rejected.getMessage().contains("fields"), rejected.getMessage());
    }

    @Test
    public void strayMemberInsideAClause_lenientMapper_isRejectedThroughTheDeserializer() {
        String body = "{\"query\":{\"expectedResultType\":\"COUNT\",\"phenotypicClause\":{\"type\":\"PhenotypicFilter\","
            + "\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"not\":false}}}";

        JsonMappingException rejected = assertThrows(JsonMappingException.class, () -> LENIENT.readValue(body, Envelope.class));

        assertTrue(rejected.getMessage().contains("type"), rejected.getMessage());
    }

    @Test
    public void v3Query_lenientMapper_bindsThroughTheDeserializer() throws JsonProcessingException {
        Envelope bound = LENIENT.readValue(
            "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"expectedResultType\":\"COUNT\"},\"resourceUUID\":null}",
            Envelope.class
        );

        assertEquals(ResultType.COUNT, bound.query().expectedResultType());
        assertEquals(List.of("\\demographics\\AGE\\"), bound.query().select());
    }

    @Test
    public void nullQuery_bindsAsNull() throws JsonProcessingException {
        assertNull(LENIENT.readValue("{\"query\":null}", Envelope.class).query());
    }

    @Test
    public void v1Query_lenientMapper_withoutTheDeserializer_stillBindsAsAnUnfilteredQuery() throws JsonProcessingException {
        Query bound = LENIENT.readValue(V1_QUERY, Query.class);

        assertEquals(ResultType.COUNT, bound.expectedResultType());
        assertEquals(List.of(), bound.allFilters());
    }

    @Test
    public void persistedQueryWithAHistoricalExtraMember_lenientMapper_stillBinds() throws JsonProcessingException {
        Query bound = LENIENT.readValue("{\"select\":[],\"phenotypicClause\":null,\"historical\":\"value\"}", Query.class);

        assertNull(bound.phenotypicClause());
        assertEquals(List.of(), bound.select());
    }
}
