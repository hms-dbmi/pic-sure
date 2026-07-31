package edu.harvard.dbmi.avillach.dictionary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.commons.openapi.OpenApiExport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regenerates {@code docs/api/picsure-dictionary.openapi.json} from the running context and asserts the contract invariants that must hold
 * in it. The document is a build artifact of record, committed alongside the code: a diff in it is a diff in the public wire, and this test
 * is what makes that visible in review.
 *
 * <p>springdoc is a TEST-scope dependency, so the endpoint this reads exists only while this test runs -- the service ships no live
 * {@code /v3/api-docs}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
class OpenApiExportTest {

    private static final String ARTIFACT = "picsure-dictionary";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsTheDocumentAndHoldsTheSharedContractInvariants() throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode document = OpenApiExport.write(ARTIFACT, raw);

        OpenApiExport.assertSharedContractInvariants(document);

        // Representative shape: /concepts answers with the SHARED PaginatedResponse, not Spring's PageImpl.
        JsonNode listConcepts = OpenApiExport.operation(document, "/concepts", "post");
        OpenApiExport.assertRespondsWith(listConcepts, "application/json", "PaginatedResponseConcept");
        // ...and its page parameter documents the 0-base, because the shared record deliberately does not fix one.
        OpenApiExport.assertParameterDescribes(listConcepts, "page_number", "ZERO-BASED");
    }
}
