package edu.harvard.hms.dbmi.avillach.resource.visualization;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.hms.dbmi.avillach.resource.visualization.model.domain.Query;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.DataProcessingProcessingService;
import edu.harvard.hms.dbmi.avillach.resource.visualization.service.HpdsService;
import org.junit.jupiter.api.*;

import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.*;


class VisualizationResourceTests extends VisualizationResource {

    static DataProcessingProcessingService dataProcessingService;
    static HpdsService hpdsService;

    @BeforeAll
    static void setUp() {
        dataProcessingService = new DataProcessingProcessingService();
        hpdsService = new HpdsService();
    }

    public VisualizationResourceTests() {
        super(dataProcessingService, hpdsService);
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
