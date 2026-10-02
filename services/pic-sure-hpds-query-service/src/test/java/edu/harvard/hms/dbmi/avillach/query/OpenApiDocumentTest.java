package edu.harvard.hms.dbmi.avillach.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.openapi.OpenApiConfiguration;
import edu.harvard.hms.dbmi.avillach.openapi.OpenApiDocumentAssertions;

/**
 * The live document is served unauthenticated, names this service, carries the bearer scheme, and covers every visible handler with a
 * summarised operation. An endpoint cannot vanish from the document, and the annotation pass cannot skip one, without failing here. The
 * remaining tests pin each handler's request and response schema or media type, and the fields the frontend and the Python adapter read.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest {

    private static final String QUERY = "/hpds/{backend}/query";
    private static final String SYNC = "/hpds/{backend}/query/sync";
    private static final String STATUS = "/hpds/{backend}/query/{id}/status";
    private static final String RESULT = "/hpds/{backend}/query/{id}/result";
    private static final String SIGNED_URL = "/hpds/{backend}/query/{id}/signed-url";
    private static final String METADATA = "/hpds/{backend}/query/{id}/metadata";
    private static final String OPEN_QUERY = "/hpds/open/query";
    private static final String OPEN_SYNC = "/hpds/open/query/sync";
    private static final String SEARCH = "/hpds/{backend}/search";
    private static final String VALUES = "/hpds/{backend}/search/values";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void documentCoversEveryVisibleHandler() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(body);

        assertThat(document.path("info").path("title").asText()).isEqualTo("pic-sure-hpds-query-service");
        assertThat(document.path("info").path("version").asText()).isNotBlank();
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        OpenApiDocumentAssertions.assertCovers(document, handlerMapping);
    }

    @Test
    void signedUrlAnswersTheSignedUrlObject() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "post", SIGNED_URL, "200", "SignedUrlResponse");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "SignedUrlResponse", "signedUrl");
    }

    @Test
    void searchBindsTheDocumentedSearchRequestAndValuesTakesNoBody() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertRequestSchema(document, "post", SEARCH, "SearchRequest");
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "SearchRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", SEARCH, "200", "SearchResults");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "SearchResults", "results", "searchQuery");
        assertThat(operation(document, "get", VALUES).has("requestBody")).isFalse();
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", VALUES, "200", "PaginatedSearchResultString");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "PaginatedSearchResultString", "results", "page", "total");
        assertThat(
            document.path("components").path("schemas").path("PaginatedSearchResultString").path("properties").path("results").path("items")
                .path("type").asText()
        ).isEqualTo("string");
    }

    @Test
    void openAggregateEndpointsBindTheDocumentedQueryRequest() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertRequestSchema(document, "post", OPEN_QUERY, "HpdsQueryRequest");
        OpenApiDocumentAssertions.assertRequestSchema(document, "post", OPEN_SYNC, "HpdsQueryRequest");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", OPEN_QUERY, "200", "QueryStatus");
    }

    @Test
    void queryLifecycleEndpointsBindTheDocumentedQueryRequest() throws Exception {
        JsonNode document = document();

        for (String path : List.of(QUERY, SYNC, STATUS, RESULT, SIGNED_URL)) {
            OpenApiDocumentAssertions.assertRequestSchema(document, "post", path, "HpdsQueryRequest");
        }
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "HpdsQueryRequest");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "HpdsQueryRequest", "query");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "Query", "select", "authorizationFilters", "phenotypicClause", "genomicFilters", "expectedResultType", "picsureId",
            "id"
        );
        assertThat(document.path("components").path("schemas").path("HpdsQueryRequest").path("properties").size()).isEqualTo(1);
        assertThat(operation(document, "get", METADATA).has("requestBody")).isFalse();
        assertThat(operation(document, "post", METADATA).has("requestBody")).isFalse();
    }

    @Test
    void queryStatusIsTheResponseOfSubmitStatusAndMetadata() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "post", QUERY, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", STATUS, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", METADATA, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertResponseSchema(document, "post", METADATA, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "QueryStatus", "picsureResultId", "resourceResultId", "status", "resourceStatus", "resultMetadata"
        );
    }

    @Test
    void syncAndResultBodiesAreDocumentedByMediaTypeAndResultType() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertMediaType(document, "post", SYNC, "200", "application/json", "string");
        OpenApiDocumentAssertions.assertMediaType(document, "post", OPEN_SYNC, "200", "application/json", "string");
        OpenApiDocumentAssertions.assertMediaType(document, "post", RESULT, "200", "application/octet-stream", "string");
        OpenApiDocumentAssertions.assertMediaType(document, "post", RESULT, "200", "text/plain", "string");

        JsonNode sync = operation(document, "post", SYNC).path("responses").path("200").path("content").path("application/json");
        assertThat(sync.path("schema").path("description").asText()).contains(
            "COUNT", "CROSS_COUNT", "OBSERVATION_CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "INFO_COLUMN_LISTING",
            "VARIANT_COUNT_FOR_QUERY", "VARIANT_LIST_FOR_QUERY", "VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"
        );
        JsonNode examples = sync.path("examples");
        assertThat(examples.path("VARIANT_COUNT_FOR_QUERY with genomic filters").path("value").path("count").isInt()).isTrue();
        assertThat(examples.path("VARIANT_COUNT_FOR_QUERY without genomic filters").path("value").path("count").isTextual()).isTrue();
        assertThat(examples.path("VARIANT_COUNT_FOR_QUERY without genomic filters").path("value").path("count").asText()).isEqualTo("0");

        JsonNode openSync = operation(document, "post", OPEN_SYNC).path("responses").path("200").path("content").path("application/json");
        assertThat(openSync.path("schema").path("description").asText())
            .contains("COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "VARIANT_COUNT_FOR_QUERY");
        assertThat(openSync.path("examples").path("COUNT below the threshold").path("value").asText()).isEqualTo("< 10");
    }

    private JsonNode document() throws Exception {
        return objectMapper
            .readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode operation(JsonNode document, String method, String path) {
        return document.path("paths").path(path).path(method);
    }
}
