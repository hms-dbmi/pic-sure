package edu.harvard.hms.dbmi.avillach.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.fasterxml.jackson.databind.node.MissingNode;

import edu.harvard.hms.dbmi.avillach.openapi.OpenApiConfiguration;
import edu.harvard.hms.dbmi.avillach.openapi.OpenApiDocumentAssertions;
import edu.harvard.hms.dbmi.avillach.query.query.HpdsQueryRequest;

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

        assertThat(document.path("info").path("title").asText()).isEqualTo("PIC-SURE High Performance Data Store (HPDS) Query Service");
        assertThat(document.path("info").path("version").asText()).isNotBlank();
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        assertThat(document.path("security").get(0).has(OpenApiConfiguration.BEARER_SCHEME)).isTrue();
        OpenApiDocumentAssertions.assertCovers(document, handlerMapping);
    }

    @Test
    void signedUrlAnswersTheSignedUrlObject() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "get", SIGNED_URL, "200", "SignedUrlResponse");
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
    void theAuthGroupDocumentsExactAuthPathsAndNothingOpen() throws Exception {
        JsonNode document = groupDocument("auth");

        assertThat(document.path("info").path("title").asText()).isEqualTo("PIC-SURE High Performance Data Store (HPDS) Query Service");
        List<String> paths = new ArrayList<>();
        document.path("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).containsExactlyInAnyOrder(
            "/hpds/auth/query", "/hpds/auth/query/sync", "/hpds/auth/query/{id}/status", "/hpds/auth/query/{id}/result",
            "/hpds/auth/query/{id}/signed-url", "/hpds/auth/query/{id}/metadata", "/hpds/auth/search", "/hpds/auth/search/values"
        );
        document.path("paths").forEach(item -> item.forEach(operation -> {
            assertThat(backendParameter(operation).isMissingNode()).isTrue();
            assertThat(operation.path("description").asText()).doesNotContain("`open`", "{backend}");
        }));
    }

    @Test
    void theOpenGroupDocumentsOnlyTheOpenSyncAndWhatOpenAccessDoesNotAccept() throws Exception {
        JsonNode document = groupDocument("open");

        assertThat(document.path("info").path("title").asText()).isEqualTo("PIC-SURE High Performance Data Store (HPDS) Open Access");
        List<String> paths = new ArrayList<>();
        document.path("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).containsExactly(OPEN_SYNC);
        JsonNode sync = operation(document, "post", OPEN_SYNC);
        assertThat(sync.path("description").asText()).contains(
            "Not supported on open access", "Leave `genomicFilters` empty", "PATIENTS and the DATAFRAME types", "/hpds/open/query/sync"
        );
        JsonNode examples = sync.path("requestBody").path("content").path("application/json").path("examples");
        assertThat(examples.size()).isEqualTo(5);
        examples.forEach(example -> {
            assertThat(example.path("description").asText()).contains("`genomicFilters` stays empty");
            assertThat(example.path("value").path("query").path("genomicFilters")).isEmpty();
        });
        assertThat(examples.path("CROSS_COUNT").path("value").path("query").path("select")).isEmpty();
        JsonNode response = sync.path("responses").path("200").path("content").path("application/json");
        assertThat(response.path("examples").path("COUNT below the threshold").path("value").asText()).isEqualTo("< 10");
    }

    @Test
    void eachAuthQueryOperationListsItsResultTypes() throws Exception {
        JsonNode document = groupDocument("auth");

        assertThat(operation(document, "post", "/hpds/auth/query").path("description").asText())
            .contains("**Result types**: PATIENTS, DATAFRAME", "POST /hpds/auth/query/sync", "-X POST");
        assertThat(operation(document, "post", "/hpds/auth/query/sync").path("description").asText())
            .contains("**Result types**: COUNT", "VARIANT_LIST_FOR_QUERY", "AGGREGATE_VCF_EXCERPT", "curl -X POST");
        JsonNode tag = null;
        for (JsonNode candidate : document.path("tags")) {
            if (candidate.path("name").asText().equals("Queries")) {
                tag = candidate;
            }
        }
        assertThat(tag).isNotNull();
        assertThat(tag.path("description").asText()).contains("authorized HPDS backend", "Open access").doesNotContain("|");
    }

    @Test
    void asyncOperationsWalkTheCallerFromSubmitToResult() throws Exception {
        JsonNode document = document();

        assertThat(operation(document, "post", QUERY).path("description").asText()).contains(
            "`picsureResultId`", "**Submit**", "**Poll**", "**Download**", "GET /hpds/auth/query/{id}/status",
            "GET /hpds/auth/query/{id}/result", "jq -r .picsureResultId"
        );
        assertThat(operation(document, "get", STATUS).path("description").asText())
            .contains("| `QUEUED` |", "| `PENDING` |", "| `AVAILABLE` |", "| `ERROR` |", "Step 2");
        assertThat(operation(document, "get", RESULT).path("description").asText()).contains("Step 3", "answers 400", "DATAFRAME_PFB");
        assertThat(operation(document, "get", SIGNED_URL).path("description").asText()).contains("`signedUrl`", "answers 400");
    }

    @Test
    void searchOperationsExplainTheirUse() throws Exception {
        JsonNode document = document();

        assertThat(operation(document, "post", SEARCH).path("description").asText())
            .contains("POST /picsure/dictionary/concepts", "results.info", "curl -X POST");
        JsonNode values = operation(document, "get", VALUES);
        assertThat(values.path("description").asText()).contains("one-based", "searchGenomicValues", "curl ");
        for (String name : List.of("backend", "genomicConceptPath", "query", "page", "size")) {
            JsonNode parameter = null;
            for (JsonNode candidate : values.path("parameters")) {
                if (candidate.path("name").asText().equals(name)) {
                    parameter = candidate;
                }
            }
            assertThat(parameter).as(name).isNotNull();
            assertThat(parameter.path("description").asText()).as(name).isNotBlank();
        }
    }

    @Test
    void everyRequestExampleIsACompleteBodyForItsResultType() throws Exception {
        JsonNode document = document();

        Map<String, List<String>> expected = Map.of(
            QUERY, List.of("PATIENTS", "DATAFRAME", "DATAFRAME_TIMESERIES", "DATAFRAME_PFB"), SYNC,
            List.of(
                "COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "OBSERVATION_CROSS_COUNT",
                "INFO_COLUMN_LISTING", "VARIANT_COUNT_FOR_QUERY", "VARIANT_LIST_FOR_QUERY", "VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"
            )
        );
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            JsonNode examples =
                operation(document, "post", entry.getKey()).path("requestBody").path("content").path("application/json").path("examples");
            assertThat(examples.size()).as("%s example count", entry.getKey()).isEqualTo(entry.getValue().size());
            for (String resultType : entry.getValue()) {
                JsonNode body = examples.path(resultType).path("value");
                assertThat(body.path("query").path("expectedResultType").asText()).as("%s %s", entry.getKey(), resultType)
                    .isEqualTo(resultType);
                objectMapper.treeToValue(body, HpdsQueryRequest.class);
            }
        }
    }

    @Test
    void submitAndSyncBindTheDocumentedQueryRequest() throws Exception {
        JsonNode document = document();

        for (String path : List.of(QUERY, SYNC)) {
            OpenApiDocumentAssertions.assertRequestSchema(document, "post", path, "HpdsQueryRequest");
        }
        OpenApiDocumentAssertions.assertSchemaDocumented(document, "HpdsQueryRequest");
        OpenApiDocumentAssertions.assertSchemaHasFields(document, "HpdsQueryRequest", "query");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "Query", "select", "phenotypicClause", "genomicFilters", "expectedResultType", "picsureId", "id"
        );
        assertThat(document.path("components").path("schemas").path("HpdsQueryRequest").path("properties").size()).isEqualTo(1);
        assertThat(operation(document, "get", METADATA).has("requestBody")).isFalse();
    }

    @Test
    void storedQueryReadsAreGetWithNoBodyAndDocument405() throws Exception {
        JsonNode document = document();

        for (String path : List.of(STATUS, RESULT, SIGNED_URL, METADATA)) {
            assertThat(operation(document, "get", path).isMissingNode()).as("GET %s", path).isFalse();
            assertThat(operation(document, "get", path).has("requestBody")).as("GET %s request body", path).isFalse();
            assertThat(operation(document, "post", path).isMissingNode()).as("POST %s", path).isTrue();
            assertThat(operation(document, "get", path).path("responses").has("405")).as("GET %s 405", path).isTrue();
            assertThat(operation(document, "get", path).path("responses").path("400").path("description").asText()).as("GET %s 400", path)
                .doesNotContain("body");
        }
    }

    @Test
    void queryStatusIsTheResponseOfSubmitStatusAndMetadata() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertResponseSchema(document, "post", QUERY, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", STATUS, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertResponseSchema(document, "get", METADATA, "200", "QueryStatus");
        OpenApiDocumentAssertions.assertSchemaHasFields(
            document, "QueryStatus", "picsureResultId", "resourceResultId", "status", "resourceStatus", "resultMetadata"
        );
    }

    @Test
    void syncAndResultBodiesAreDocumentedByMediaTypeAndResultType() throws Exception {
        JsonNode document = document();

        OpenApiDocumentAssertions.assertMediaType(document, "post", SYNC, "200", "application/json", "string");
        OpenApiDocumentAssertions.assertMediaType(document, "get", RESULT, "200", "application/octet-stream", "string");
        OpenApiDocumentAssertions.assertMediaType(document, "get", RESULT, "200", "text/plain", "string");

        JsonNode sync = operation(document, "post", SYNC).path("responses").path("200").path("content").path("application/json");
        assertThat(sync.path("schema").path("description").asText()).contains(
            "COUNT", "CROSS_COUNT", "OBSERVATION_CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "INFO_COLUMN_LISTING",
            "VARIANT_COUNT_FOR_QUERY", "VARIANT_LIST_FOR_QUERY", "VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"
        );
        JsonNode examples = sync.path("examples");
        assertThat(examples.path("VARIANT_COUNT_FOR_QUERY with genomic filters").path("value").path("count").isInt()).isTrue();
        assertThat(examples.path("VARIANT_COUNT_FOR_QUERY without genomic filters").path("value").path("count").isTextual()).isTrue();
        assertThat(examples.path("VARIANT_COUNT_FOR_QUERY without genomic filters").path("value").path("count").asText()).isEqualTo("0");
        assertThat(sync.path("schema").path("description").asText()).doesNotContain("obfuscated");
    }

    private JsonNode groupDocument(String group) throws Exception {
        return objectMapper.readTree(
            mockMvc.perform(get("/v3/api-docs/" + group)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
        );
    }

    private JsonNode document() throws Exception {
        return objectMapper
            .readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode backendParameter(JsonNode operation) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (parameter.path("name").asText().equals("backend")) {
                return parameter;
            }
        }
        return MissingNode.getInstance();
    }

    private static JsonNode operation(JsonNode document, String method, String path) {
        return document.path("paths").path(path).path(method);
    }
}
