package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class QueryTest {


    @Test
    public void jacksonSerialization_validValues() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "//abc//123///", Set.of("turtle"), 10.0, 20.0, true);

        List<AuthorizationFilter> authorizationFilters = List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs123", "phs456")));

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(true, List.of(phenotypicFilter), Operator.AND);
        PhenotypicSubquery phenotypicSubquery2 = new PhenotypicSubquery(true, List.of(phenotypicFilter), Operator.AND);

        PhenotypicSubquery phenotypicQuery =
            new PhenotypicSubquery(null, List.of(phenotypicSubquery, phenotypicSubquery2, phenotypicFilter), Operator.OR);
        Query query = new Query(List.of("PATIENT_ID"), authorizationFilters, phenotypicQuery, List.of(), ResultType.COUNT, null, null);

        String serialized = objectMapper.writeValueAsString(query);
        System.out.println(serialized);

        Query deserialized = objectMapper.readValue(serialized, Query.class);

        assertEquals(query, deserialized);
    }

    @Test
    public void jacksonSerialization_nullableFieldsNormalize() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        Query query = new Query(null, null, null, null, ResultType.COUNT, null, null);

        String serialized = objectMapper.writeValueAsString(query);
        System.out.println(serialized);

        Query deserialized = objectMapper.readValue(serialized, Query.class);

        assertEquals(List.of(), deserialized.select());
        assertEquals(List.of(), deserialized.authorizationFilters());
        assertNull(deserialized.phenotypicClause());
        assertEquals(List.of(), deserialized.genomicFilters());
        assertEquals(ResultType.COUNT, deserialized.expectedResultType());
        assertNull(deserialized.picsureId());
        assertNull(deserialized.id());
    }

    @Test
    public void jacksonDeserialization_absentExpectedResultType_isRefused() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(JsonProcessingException.class, () -> objectMapper.readValue("{\"select\":[]}", Query.class));
    }

    @Test
    public void jacksonDeserialization_nullExpectedResultType_isRefused() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(
            JsonProcessingException.class, () -> objectMapper.readValue("{\"select\":[],\"expectedResultType\":null}", Query.class)
        );
    }

    @Test
    public void jacksonDeserialization_ordinalExpectedResultType_binds() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        assertEquals(ResultType.DATAFRAME, objectMapper.readValue("{\"expectedResultType\":1}", Query.class).expectedResultType());
        assertEquals(ResultType.DATAFRAME, objectMapper.readValue("{\"expectedResultType\":\"1\"}", Query.class).expectedResultType());
    }

    /**
     * In-process construction stays permissive on purpose. {@code CountV3Processor} builds per-concept probe queries with no result type,
     * and its caller swallows exceptions into a -1 count, so enforcing here would silently break every cross count.
     */
    @Test
    public void construction_nullExpectedResultType_isAllowed() {
        assertNull(new Query(List.of(), List.of(), null, List.of(), null, null, null).expectedResultType());
    }

    @Test
    public void jacksonSerialization_validNullSecondLevelValues() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        PhenotypicFilter phenotypicFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "//abc//123///", null, null, null, null);

        List<AuthorizationFilter> authorizationFilters = List.of(new AuthorizationFilter(null, null));

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(null, List.of(phenotypicFilter), null);
        PhenotypicSubquery phenotypicSubquery2 = new PhenotypicSubquery(null, List.of(phenotypicFilter), null);

        PhenotypicSubquery phenotypicQuery =
            new PhenotypicSubquery(null, List.of(phenotypicSubquery, phenotypicSubquery2, phenotypicFilter), null);
        Query query = new Query(List.of("PATIENT_ID"), authorizationFilters, phenotypicQuery, List.of(), ResultType.COUNT, null, null);

        String serialized = objectMapper.writeValueAsString(query);
        System.out.println(serialized);

        Query deserialized = objectMapper.readValue(serialized, Query.class);

        assertEquals(query, deserialized);
    }

    @Test
    public void allFilters_nullPhenotypicClausesListFromJson_returnsEmptyInsteadOfNpe() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        // A client can legally omit phenotypicClauses on a subquery; that must not NPE downstream.
        String json = "{\"phenotypicClause\":{\"phenotypicClauses\":null,\"operator\":\"AND\"},\"expectedResultType\":\"COUNT\"}";

        Query deserialized = objectMapper.readValue(json, Query.class);

        assertEquals(List.of(), deserialized.allFilters());
        PhenotypicSubquery sub = (PhenotypicSubquery) deserialized.phenotypicClause();
        assertEquals(List.of(), sub.phenotypicClauses());
    }

    @Test
    public void allFilters_nullElementInPhenotypicClausesFromJson_isIgnored() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        String json = "{\"expectedResultType\":\"COUNT\",\"phenotypicClause\":{\"phenotypicClauses\":[null,"
            + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\abc\\\\\",\"min\":1.0}],\"operator\":\"AND\"}}";

        Query deserialized = objectMapper.readValue(json, Query.class);

        List<PhenotypicFilter> filters = deserialized.allFilters();
        assertEquals(1, filters.size());
        assertEquals("\\abc\\", filters.get(0).conceptPath());
    }

    @Test
    public void generateId_nullId_createNewId() {
        Query query = new Query(List.of("PATIENT_ID"), List.of(), null, List.of(), ResultType.COUNT, null, null);

        query = query.generateId();
        assertNotNull(query.id());
    }

    @Test
    public void generateId_idExists_doNotReplaceId() {
        UUID uuid = UUID.randomUUID();
        Query query = new Query(List.of("PATIENT_ID"), List.of(), null, List.of(), ResultType.COUNT, null, uuid);

        query = query.generateId();
        assertEquals(uuid, query.id());
    }
}
