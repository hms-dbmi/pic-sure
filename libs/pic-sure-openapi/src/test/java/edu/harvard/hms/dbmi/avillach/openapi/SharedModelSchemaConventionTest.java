package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.ContinuousBinningResponse;
import edu.harvard.dbmi.avillach.domain.PaginatedSearchResult;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

/**
 * The models two or more services exchange meet the {@code @Schema} convention in a served document. The controller here exists only to put
 * each shared model into a document; the services' own document tests pin where they really appear.
 */
@SpringBootTest(classes = {OpenApiTestApplication.class, SharedModelSchemaConventionTest.SharedModelController.class})
@AutoConfigureMockMvc
class SharedModelSchemaConventionTest {

    @RestController
    static class SharedModelController {
        @PostMapping("/shared/query")
        public QueryStatus query(@RequestBody Query query) {
            return new QueryStatus();
        }

        @GetMapping("/shared/search")
        public SearchResults search() {
            return new SearchResults();
        }

        @GetMapping("/shared/search/values")
        public PaginatedSearchResult<String> values() {
            return null;
        }

        @GetMapping("/shared/signed-url")
        public SignedUrlResponse signedUrl() {
            return null;
        }

        @GetMapping("/shared/info")
        public ResourceInfo info() {
            return new ResourceInfo();
        }

        @PostMapping("/shared/bin/continuous")
        public ContinuousBinningResponse binContinuous() {
            return null;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private JsonNode document;

    @BeforeEach
    void loadDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        document = new ObjectMapper().readTree(body);
    }

    @Test
    void apiModelMeetsTheConvention() {
        OpenApiDocumentAssertions.assertSchemaDocumented(
            document, "QueryStatus", "SearchResults", "PaginatedSearchResultString", "SignedUrlResponse", "ResourceInfo", "QueryFormat"
        );
    }

    @Test
    void hpdsModelMeetsTheConvention() {
        OpenApiDocumentAssertions.assertSchemaDocumented(
            document, "Query", "AuthorizationFilter", "GenomicFilter", "PhenotypicClause", "PhenotypicFilter", "PhenotypicSubquery"
        );
    }

    @Test
    void binningModelMeetsTheConvention() {
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "ContinuousBinningResponse");
    }

    @Test
    void requiredMembersAreListed() {
        assertRequired("QueryStatus", "status", "picsureResultId");
        assertRequired("PaginatedSearchResultString", "results", "page", "total");
        assertRequired("SignedUrlResponse", "signedUrl");
        assertRequired("ResourceInfo", "id", "name");
        assertRequired("Query", "expectedResultType");
        assertRequired("ContinuousBinningResponse", "bins");
    }

    private void assertRequired(String schemaName, String... members) {
        List<String> required = new ArrayList<>();
        document.path("components").path("schemas").path(schemaName).path("required").forEach(node -> required.add(node.asText()));
        assertThat(required).as(schemaName + ".required").containsExactlyInAnyOrder(members);
    }
}
