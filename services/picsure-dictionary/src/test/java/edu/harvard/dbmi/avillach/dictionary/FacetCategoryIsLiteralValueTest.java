package edu.harvard.dbmi.avillach.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptFilterQueryGenerator;
import edu.harvard.dbmi.avillach.dictionary.concept.ConceptService;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.filter.QueryParamPair;
import edu.harvard.dbmi.avillach.logging.LoggingClient;

/**
 * Pins what the service does with a facet category that holds punctuation. Each request goes over real HTTP to an embedded server whose
 * production beans read the seeded PostgreSQL container. The category is accepted with a 200, the query runs with the category as a bound
 * value, no concept matches because no category has that name, and the seeded data answers the same afterwards. The request is not rejected
 * with a 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class FacetCategoryIsLiteralValueTest {

    static final String REAL_CATEGORY = "study_ids_dataset_ids";
    static final String REAL_FACET = "phs000007";

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

    @Autowired
    private ConceptFilterQueryGenerator queryGenerator;

    @MockitoSpyBean
    private ConceptService conceptService;

    @MockitoBean
    private LoggingClient loggingClient;

    /**
     * Audit logging is switched on, as it is in a deployment with a logging service configured. With it off, {@link AuditLoggingFilter}
     * turns an uncaught handler exception into a 200 with no body, which would hide a failed query.
     */
    @BeforeEach
    void enableAuditLogging() {
        when(loggingClient.isEnabled()).thenReturn(true);
    }

    /**
     * A category with a quote, a semicolon, a double hyphen or parentheses searches concepts with a 200 and an empty page. The concept
     * service receives the category unchanged, so the request reached the query layer, and the real category returns the same number of
     * concepts before and after.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {"Framingham's cohorts", "study ids; dataset ids", "study ids -- dataset ids", "study ids) (dataset ids",
            "Diagnosis (ICD-10)"}
    )
    void conceptSearchTreatsCategoryAsLiteralValue(String category) throws Exception {
        long conceptsBefore = totalConcepts(REAL_CATEGORY);
        assertThat(conceptsBefore).isPositive();

        ResponseEntity<String> response = rest.postForEntity("/concepts", filterWithCategory(category), String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody());
        assertThat(page.path("totalElements").asLong()).isZero();
        assertThat(page.path("content")).isEmpty();
        verify(conceptService).countConcepts(argThat(filter -> filter.facets().getFirst().category().equals(category)));
        assertThat(totalConcepts(REAL_CATEGORY)).isEqualTo(conceptsBefore);
    }

    /** The facet count endpoint accepts the same categories with a 200. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Framingham's cohorts", "study ids; dataset ids", "study ids -- dataset ids"})
    void facetCountTreatsCategoryAsLiteralValue(String category) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/facets", filterWithCategory(category), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).isArray()).isTrue();
    }

    /** The generated search and count SQL is the same text for a real category and for one holding punctuation. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Framingham's cohorts", "study ids; dataset ids", "study ids -- dataset ids"})
    void queryTextDoesNotDependOnCategory(String category) {
        Filter real = new Filter(List.of(new Facet(REAL_FACET, REAL_CATEGORY)), "", List.of());
        Filter crafted = new Filter(List.of(new Facet(REAL_FACET, category)), "", List.of());

        QueryParamPair craftedSearch = queryGenerator.generateFilterQuery(crafted, Pageable.unpaged());
        QueryParamPair craftedCount = queryGenerator.generateCountQuery(crafted);

        assertThat(craftedSearch.query()).isEqualTo(queryGenerator.generateFilterQuery(real, Pageable.unpaged()).query());
        assertThat(craftedCount.query()).isEqualTo(queryGenerator.generateCountQuery(real).query());
        assertThat(craftedSearch.params().getValues()).containsValue(category);
        assertThat(craftedCount.params().getValues()).containsValue(category);
    }

    private long totalConcepts(String category) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/concepts", filterWithCategory(category), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("totalElements").asLong();
    }

    private HttpEntity<String> filterWithCategory(String category) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> filter = Map.of("facets", List.of(Map.of("name", REAL_FACET, "category", category)), "search", "");
        return new HttpEntity<>(objectMapper.writeValueAsString(filter), headers);
    }
}
