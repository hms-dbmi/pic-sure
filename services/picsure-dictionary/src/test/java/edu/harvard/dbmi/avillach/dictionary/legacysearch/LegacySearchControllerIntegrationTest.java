package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacyResponse;
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

import java.io.IOException;
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
    void shouldGetLegacyResponseByStudyID() throws IOException {
        String jsonString = """
            {"query":{"searchTerm":"phs000007","includedTags":[],"excludedTags":[],"returnTags":"true","offset":0,"limit":100}}
            """;

        ResponseEntity<LegacyResponse> legacyResponseResponseEntity = legacySearchController.legacySearch(jsonString);
        System.out.println(legacyResponseResponseEntity);
        Assertions.assertEquals(HttpStatus.OK, legacyResponseResponseEntity.getStatusCode());
        LegacyResponse legacyResponseBody = legacyResponseResponseEntity.getBody();
        Assertions.assertNotNull(legacyResponseBody);
        Results results = legacyResponseBody.results();
        List<SearchResult> searchResults = results.searchResults();
        searchResults.forEach(searchResult -> Assertions.assertEquals("phs000007", searchResult.result().studyId()));
    }

}
