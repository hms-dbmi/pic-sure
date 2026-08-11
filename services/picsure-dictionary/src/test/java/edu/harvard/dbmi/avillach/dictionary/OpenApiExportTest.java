package edu.harvard.dbmi.avillach.dictionary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.commons.openapi.OpenApiExport;
import java.util.List;
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

        // The dictionary's WHOLE surface, pinned. springdoc documents only @RestController beans, so a controller
        // left on plain @Controller serves traffic while silently vanishing from the document -- which is exactly
        // what happened here before: six @Controller beans reduced this document to the two paths that happened to
        // carry an @Operation. Adding an endpoint is now a deliberate edit to this list.
        OpenApiExport.assertPathsAreExactly(
            document,
            List.of(
                "/concepts", "/concepts/detail", "/concepts/detail/{dataset}", "/concepts/dump", "/concepts/hierarchy/{dataset}",
                "/concepts/tree", "/concepts/tree/{dataset}", "/dashboard", "/dashboard-drawer", "/dashboard-drawer/{id}", "/facets",
                "/facets/{facetCategory}/{facet}", "/info", "/search"
            )
        );

        // Representative shape: /concepts answers with the SHARED PaginatedResponse, not Spring's PageImpl.
        JsonNode listConcepts = OpenApiExport.operation(document, "/concepts", "post");
        OpenApiExport.assertRespondsWith(listConcepts, "application/json", "PaginatedResponseConcept");
        // ...and its page parameter documents the 0-base, because the shared record deliberately does not fix one.
        OpenApiExport.assertParameterDescribes(listConcepts, "page_number", "ZERO-BASED");
    }
}
