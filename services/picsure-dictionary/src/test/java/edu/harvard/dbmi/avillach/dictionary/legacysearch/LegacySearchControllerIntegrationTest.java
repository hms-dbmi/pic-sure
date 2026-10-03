package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacyResponse;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchCriteria;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Results;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.SearchResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

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

    @Test
    void shouldGetLegacyResponseByStudyID() {
        ResponseEntity<LegacyResponse> legacyResponseResponseEntity = legacySearchController.legacySearch(searchFor("phs000007"));
        System.out.println(legacyResponseResponseEntity);
        Assertions.assertEquals(HttpStatus.OK, legacyResponseResponseEntity.getStatusCode());
        LegacyResponse legacyResponseBody = legacyResponseResponseEntity.getBody();
        Assertions.assertNotNull(legacyResponseBody);
        Results results = legacyResponseBody.results();
        List<SearchResult> searchResults = results.searchResults();
        searchResults.forEach(searchResult -> Assertions.assertEquals("phs000007", searchResult.result().studyId()));
    }

    @Test
    void shouldHandleORRequest() {
        ResponseEntity<LegacyResponse> legacyResponseResponseEntity = legacySearchController.legacySearch(searchFor("age"));
        Assertions.assertEquals(HttpStatus.OK, legacyResponseResponseEntity.getStatusCode());
        LegacyResponse legacyResponseBody = legacyResponseResponseEntity.getBody();
        Assertions.assertNotNull(legacyResponseBody);
        Results results = legacyResponseBody.results();
        List<SearchResult> ageSearchResults = results.searchResults();
        Assertions.assertEquals(4, ageSearchResults.size());

        legacyResponseResponseEntity = legacySearchController.legacySearch(searchFor("physical|age"));
        Assertions.assertEquals(HttpStatus.OK, legacyResponseResponseEntity.getStatusCode());
        legacyResponseBody = legacyResponseResponseEntity.getBody();
        Assertions.assertNotNull(legacyResponseBody);
        results = legacyResponseBody.results();
        List<SearchResult> physicalORAgeSearchResults = results.searchResults();
        Assertions.assertEquals(5, physicalORAgeSearchResults.size());

        // Verify that age|physical has expanded the search results
        Assertions.assertNotEquals(ageSearchResults.size(), physicalORAgeSearchResults.size());

        // Verify the OR statement has more results
        Assertions.assertTrue(ageSearchResults.size() < physicalORAgeSearchResults.size());
    }

    private static LegacySearchQuery searchFor(String searchTerm) {
        return new LegacySearchQuery(new LegacySearchCriteria(searchTerm, 100));
    }

}
