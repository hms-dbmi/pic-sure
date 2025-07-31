package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class QueryTest {


    @Test
    public void jacksonSerialization_validValues() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        PhenotypicFilter phenotypicFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "//abc//123///", List.of("turtle"), 10.0, 20.0, true);

        List<AuthorizationFilter> authorizationFilters = List.of(new AuthorizationFilter("\\_consents\\", List.of("phs123", "phs456")));

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
    public void jacksonSerialization_validNullValues() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        Query query = new Query(null, null, null, null, null, null, null);

        String serialized = objectMapper.writeValueAsString(query);
        System.out.println(serialized);

        Query deserialized = objectMapper.readValue(serialized, Query.class);

        assertEquals(List.of(), deserialized.select());
        assertEquals(List.of(), deserialized.authorizationFilters());
        assertNull(deserialized.phenotypicClause());
        assertEquals(List.of(), deserialized.genomicFilters());
        assertNull(deserialized.expectedResultType());
        assertNull(deserialized.picsureId());
        assertNull(deserialized.id());
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
