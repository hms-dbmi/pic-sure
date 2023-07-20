package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.ApplicationProperties;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.ResultType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HpdsServiceTests {

    static HpdsService service;
    static ApplicationProperties properties;

    static String OPEN_REQUEST_SOURCE = "Open";
    static String AUTH_REQUEST_SOURCE = "Authorized";

    @BeforeAll
    static void setUp() {
        service = new HpdsService();
        properties = new ApplicationProperties();
    }

    @Test
    @DisplayName("Test Query Has Null Authorization token")
    void TestNullAuthHeader() {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.getResourceCredentials().put("Authorization", null);
        queryRequest.setQuery(new Query());
        Map<String, Map<String, Integer>> crossCountsMap = service.getAuthCrossCountsMap(queryRequest, ResultType.CATEGORICAL_CROSS_COUNT);
        assertNotNull(crossCountsMap);
        assertEquals(0, crossCountsMap.size());
    }

    @Test
    @DisplayName("Test Query Has No Authorization header")
    void TestNoAuthHeader() {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.setQuery(new Query());
        Map<String, Map<String, Integer>> crossCountsMap = service.getAuthCrossCountsMap(queryRequest, ResultType.CATEGORICAL_CROSS_COUNT);
        assertNotNull(crossCountsMap);
        assertEquals(0, crossCountsMap.size());
    }

    @Test
    @DisplayName("Test Query Null Result type")
    void TestNullResultType() {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.setQuery(new Query());
        Map<String, Map<String, Integer>> crossCountsMap = service.getAuthCrossCountsMap(queryRequest, null);
        assertNotNull(crossCountsMap);
        assertEquals(0, crossCountsMap.size());
    }

    @Test
    @DisplayName("Test Query Wrong Result type")
    void TestWrongResultType() {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.setQuery(new Query());
        Map<String, Map<String, Integer>> crossCountsMap = service.getAuthCrossCountsMap(queryRequest, ResultType.CROSS_COUNT);
        assertNotNull(crossCountsMap);
        assertEquals(0, crossCountsMap.size());
    }
}
