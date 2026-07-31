package edu.harvard.dbmi.avillach.visualization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.commons.openapi.OpenApiExport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regenerates {@code docs/api/pic-sure-visualization-service.openapi.json} from the running context and asserts the contract invariants
 * that must hold in it. The document is a build artifact of record, committed alongside the code: a diff in it is a diff in the public
 * wire, and this test is what makes that visible in review.
 *
 * <p>springdoc is a TEST-scope dependency, so the endpoint this reads exists only while this test runs -- the service ships no live
 * {@code /v3/api-docs}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class OpenApiExportTest {

    private static final String ARTIFACT = "pic-sure-visualization-service";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsTheDocumentAndHoldsTheSharedContractInvariants() throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode document = OpenApiExport.write(ARTIFACT, raw);

        OpenApiExport.assertSharedContractInvariants(document);

        // Representative shape: /distributions answers with the typed VisualizationResponse, and its request body is
        // the BARE v3 Query itself -- no wrapper, and no v1 envelope with credentials and a resource id beside it.
        // The wrapper is not merely unused: on the authorized path the gateway replaces the whole body with PSAMA's
        // consent-mutated bare Query, so a documented wrapper would describe a request that cannot survive that hop.
        JsonNode distributions = OpenApiExport.operation(document, "/distributions", "post");
        OpenApiExport.assertRespondsWith(distributions, "application/json", "VisualizationResponse");
        OpenApiExport.assertAcceptsSchema(distributions, "application/json", "Query");
        assertFalse(
            document.path("components").path("schemas").has("DistributionRequest"),
            "the retired DistributionRequest wrapper is back in the document"
        );
    }
}
