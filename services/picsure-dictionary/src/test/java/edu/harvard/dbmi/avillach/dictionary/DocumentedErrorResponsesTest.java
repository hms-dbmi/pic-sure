package edu.harvard.dbmi.avillach.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptService;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.logging.LoggingClient;

/**
 * Reproduces every non-2xx response the OpenAPI document declares for this service through a real HTTP request to an embedded server. The
 * controllers, services, and repositories are the production beans, reading the seeded PostgreSQL container, so each 400 and 404 comes from
 * the handler's own check against real data. The stand-ins are the audit logging client, which talks to an external logging service, and a
 * spy on {@link ConceptService} for the one case that needs the concept query itself to fail. A 500 travels through the servlet container's
 * error handling, so its body is Spring Boot's default error document.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "concept.tree.max_depth=" + DocumentedErrorResponsesTest.MAX_DEPTH
)
@ActiveProfiles("test")
@Testcontainers
class DocumentedErrorResponsesTest {

    static final int MAX_DEPTH = 3;
    static final String DATASET = "phs000007";
    static final String UNKNOWN_CONCEPT_PATH = "\\phs000007\\pht000021\\not_a_concept\\";
    static final int UNKNOWN_DATASET_ID = 987_654;
    static final int DRAWER_DATASET_ID = 17;

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private ConceptService conceptService;

    @MockitoBean
    private LoggingClient loggingClient;

    /**
     * Audit logging is switched on, as it is in a deployment with a logging service configured. {@link AuditLoggingFilter} only lets an
     * uncaught handler exception reach the servlet container's error handling when the logging client is enabled.
     */
    @BeforeEach
    void enableAuditLogging() {
        when(loggingClient.isEnabled()).thenReturn(true);
    }

    /** Paging values {@code PageRequest.of} rejects make the concept search handler fail before any query runs. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"page_size=0", "page_number=-1"})
    void conceptSearchWithInvalidPagingIs500(String paging) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/concepts?" + paging, json("{\"facets\":[],\"search\":\"\"}"), String.class);

        assertServerError(response, "/concepts");
    }

    /** A failed count query surfaces from the handler's parallel execution as a 500. */
    @Test
    void conceptSearchWhoseQueryFailsIs500() throws Exception {
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(conceptService).countConcepts(any(Filter.class));

        ResponseEntity<String> response =
            rest.postForEntity("/concepts", json("{\"facets\":[],\"search\":\"query failure probe\"}"), String.class);

        assertServerError(response, "/concepts");
    }

    @Test
    void conceptDetailForUnknownPathIs404() {
        ResponseEntity<String> response =
            rest.postForEntity("/concepts/detail/{dataset}", text(UNKNOWN_CONCEPT_PATH), String.class, DATASET);

        assertEmptyNotFound(response);
    }

    /** Both bounds of the depth check reject the request before the tree is looked up. */
    @ParameterizedTest(name = "depth={0}")
    @ValueSource(ints = {-1, MAX_DEPTH + 1})
    void conceptTreeWithDepthOutOfRangeIs400(int depth) {
        ResponseEntity<String> response =
            rest.postForEntity("/concepts/tree/{dataset}?depth={depth}", text("\\phs000007\\pht000021\\"), String.class, DATASET, depth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void conceptTreeForUnknownPathIs404() {
        ResponseEntity<String> response =
            rest.postForEntity("/concepts/tree/{dataset}?depth={depth}", text(UNKNOWN_CONCEPT_PATH), String.class, DATASET, MAX_DEPTH);

        assertEmptyNotFound(response);
    }

    @Test
    void conceptHierarchyForUnknownPathIs404() {
        ResponseEntity<String> response =
            rest.postForEntity("/concepts/hierarchy/{dataset}", text(UNKNOWN_CONCEPT_PATH), String.class, DATASET);

        assertEmptyNotFound(response);
    }

    /** Both bounds of the depth check reject the request before any dataset is read. */
    @ParameterizedTest(name = "depth={0}")
    @ValueSource(ints = {-1, MAX_DEPTH + 1})
    void allConceptTreesWithDepthOutOfRangeIs400(int depth) {
        ResponseEntity<String> response = rest.getForEntity("/concepts/tree?depth={depth}", String.class, depth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void dashboardDrawerForUnknownDatasetIdIs404() {
        ResponseEntity<String> response = rest.getForEntity("/dashboard-drawer/{id}", String.class, UNKNOWN_DATASET_ID);

        assertEmptyNotFound(response);
    }

    @Test
    void facetDetailForUnknownFacetIs404() {
        ResponseEntity<String> response =
            rest.getForEntity("/facets/{category}/{facet}", String.class, "study_ids_dataset_ids", "not_a_facet");

        assertEmptyNotFound(response);
    }

    /**
     * With a dashboard layout other than {@code default}, the drawer service answers no dataset id, so even a dataset the database holds is
     * a 404.
     */
    @Nested
    @TestPropertySource(properties = "dashboard.layout.type=bdc")
    class NonDefaultDashboardLayout {

        @Autowired
        private TestRestTemplate layoutRest;

        @Test
        void dashboardDrawerForKnownDatasetIdIs404() {
            ResponseEntity<String> response = layoutRest.getForEntity("/dashboard-drawer/{id}", String.class, DRAWER_DATASET_ID);

            assertEmptyNotFound(response);
        }
    }

    private void assertServerError(ResponseEntity<String> response, String path) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(500);
        assertThat(body.path("path").asText()).isEqualTo(path);
    }

    private static void assertEmptyNotFound(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<String> text(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new HttpEntity<>(body, headers);
    }
}
