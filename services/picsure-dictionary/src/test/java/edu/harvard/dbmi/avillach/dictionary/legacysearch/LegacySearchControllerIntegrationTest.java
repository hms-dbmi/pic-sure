package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacyResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import org.junit.jupiter.api.BeforeEach;

import java.util.List;

@SpringBootTest
@Testcontainers
class LegacySearchControllerIntegrationTest {

    @Autowired
    LegacySearchController legacySearchController;

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test
    void shouldGetConceptsByStudyID() {
        ResponseEntity<LegacyResponse> response = legacySearchController.legacySearch(new SearchRequest("phs000007"), 0, 100);

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        LegacyResponse body = response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertFalse(body.results().isEmpty());
        body.results().forEach(concept -> Assertions.assertEquals("phs000007", concept.dataset()));
    }

    @Test
    void shouldHandleORRequest() {
        List<Concept> ageResults = legacySearchController.legacySearch(new SearchRequest("age"), 0, 100).getBody().results();
        Assertions.assertEquals(4, ageResults.size());

        List<Concept> physicalOrAgeResults =
            legacySearchController.legacySearch(new SearchRequest("physical|age"), 0, 100).getBody().results();
        Assertions.assertEquals(5, physicalOrAgeResults.size());

        // Verify the OR statement has more results
        Assertions.assertTrue(ageResults.size() < physicalOrAgeResults.size());
    }

    @Test
    void shouldPageResults() {
        List<Concept> firstPage = legacySearchController.legacySearch(new SearchRequest("age"), 0, 2).getBody().results();
        List<Concept> secondPage = legacySearchController.legacySearch(new SearchRequest("age"), 1, 2).getBody().results();

        Assertions.assertEquals(2, firstPage.size());
        Assertions.assertEquals(2, secondPage.size());
        Assertions.assertNotEquals(firstPage, secondPage);
    }
}
