package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryTest  {


    @Test
    public void jacksonSerialization() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        PhenotypicFilter phenotypicFilter = new PhenotypicFilter(
                PhenotypicFilterType.FILTER, "//abc//123///", List.of("turtle"), null, null, null
        );

        List<AuthorizationFilter> authorizationFilters = List.of(new AuthorizationFilter("\\_consents\\", List.of("phs123", "phs456")));

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(true, List.of(phenotypicFilter), Operator.AND);
        PhenotypicSubquery phenotypicSubquery2 = new PhenotypicSubquery(true, List.of(phenotypicFilter), Operator.AND);

        PhenotypicSubquery phenotypicQuery = new PhenotypicSubquery(null, List.of(phenotypicSubquery, phenotypicSubquery2, phenotypicFilter), Operator.OR);
        Query query = new Query(ResultType.COUNT, authorizationFilters, List.of("PATIENT_ID"), phenotypicQuery, List.of());

        String serialized = objectMapper.writeValueAsString(query);
        System.out.println(serialized);

        Query deserialized = objectMapper.readValue(serialized, Query.class);

        assertEquals(query, deserialized);
    }

}