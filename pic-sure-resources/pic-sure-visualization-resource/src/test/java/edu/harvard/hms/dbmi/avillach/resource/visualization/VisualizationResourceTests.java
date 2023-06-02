package edu.harvard.hms.dbmi.avillach.resource.visualization;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.DataProcessingService;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.HpdsService;
import org.junit.jupiter.api.*;

import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.*;


class VisualizationResourceTests extends VisualizationResource {

    static DataProcessingService dataProcessingService;
    static HpdsService hpdsService;
    static ApplicationProperties properties;

    @BeforeAll
    static void setUp() {
        dataProcessingService = new DataProcessingService();
        hpdsService = new HpdsService();
        properties = new ApplicationProperties();
    }

    public VisualizationResourceTests() {
        super();
    }

    @Test
    @DisplayName("Test Empty Query")
    void TestEmptyQuery() {
        QueryRequest queryRequest = new QueryRequest();
        queryRequest.setQuery(new Query());
        Response response = getProcessedCrossCounts(queryRequest);
        assertNull(response.getEntity());
    }
}
