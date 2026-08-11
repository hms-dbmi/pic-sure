package edu.harvard.hms.dbmi.avillach.hpds.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.openapi.OpenApiExport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regenerates {@code docs/api/pic-sure-hpds.openapi.json} from the running context and asserts the contract invariants that must hold in
 * it. The document is a build artifact of record, committed alongside the code: a diff in it is a diff in the public wire, and this test is
 * what makes that visible in review.
 *
 * <p>The file is named for the deployable (pic-sure-hpds), not for this module's artifactId, which is the bare word {@code service}.
 *
 * <p>springdoc is a TEST-scope dependency, so the endpoint this reads exists only while this test runs -- HPDS ships no live
 * {@code /v3/api-docs}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
class OpenApiExportTest {

    private static final String ARTIFACT = "pic-sure-hpds";

    @Autowired
    private MockMvc mockMvc;

    /** Reading the document touches no audit sink; mocked so the export cannot depend on a logging service being up. */
    @MockitoBean
    private LoggingClient loggingClient;

    @Test
    void exportsTheDocumentAndHoldsTheSharedContractInvariants() throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode document = OpenApiExport.write(ARTIFACT, raw);

        OpenApiExport.assertSharedContractInvariants(document);

        // Representative shape: submission binds the BARE v3 Query and answers with the shared QueryStatusResponse.
        JsonNode query = OpenApiExport.operation(document, "/PIC-SURE/v3/query", "post");
        OpenApiExport.assertAcceptsSchema(query, "application/json", "Query");
        OpenApiExport.assertRespondsWith(query, "application/json", "QueryStatusResponse");

        // /query/sync is the one untyped v3 response. It must not claim a schema it does not have -- it must instead
        // say, in the document, that the body varies with expectedResultType.
        JsonNode sync = OpenApiExport.operation(document, "/PIC-SURE/v3/query/sync", "post");
        OpenApiExport.assertDescriptionMentions(sync, "expectedResultType");
        // ...and the JSON branch must declare that it carries an OBJECT. springdoc falls back to type: string for a
        // handler returning Object whose @Schema sets only a description, which would document a CROSS_COUNT map as a
        // JSON string -- a lie a client generator would act on, and worse than leaving it untyped.
        OpenApiExport.assertResponseType(sync, "application/json", "object");
        OpenApiExport.assertResponseType(sync, "text/plain", "string");

        // The 1-based paging divergence is documented at the endpoint, since PaginatedResponse itself cannot fix a base.
        OpenApiExport
            .assertParameterDescribes(OpenApiExport.operation(document, "/PIC-SURE/v3/search/values/", "get"), "page", "ONE-BASED");
    }
}
