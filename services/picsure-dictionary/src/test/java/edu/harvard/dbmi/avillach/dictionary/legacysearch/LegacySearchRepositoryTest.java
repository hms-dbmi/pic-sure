package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.ConceptRepository;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.SearchResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;

@SpringBootTest
@Testcontainers
public class LegacySearchRepositoryTest {

    @Autowired
    LegacySearchRepository subject;

    @Autowired
    ConceptRepository conceptService;

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
    void shouldGetLegacySearchResults() {
        List<SearchResult> searchResults = subject.getLegacySearchResults(new Filter(List.of(), "", List.of()), Pageable.unpaged());

        Assertions.assertEquals(30, searchResults.size());
    }

    @Test
    void shouldGetLegacySearchResultsBySearch() {
        List<SearchResult> searchResults =
            subject.getLegacySearchResults(new Filter(List.of(), "phs000007", List.of()), Pageable.unpaged());

        searchResults.forEach(searchResult -> Assertions.assertEquals("phs000007", searchResult.result().studyId()));

    }

    @Test
    void shouldGetLegacySearchResultsByPageSize() {
        List<SearchResult> searchResults = subject.getLegacySearchResults(new Filter(List.of(), "", List.of()), Pageable.ofSize(5));

        Assertions.assertEquals(5, searchResults.size());
    }

    @Test
    void legacySearchResultShouldGetEqualCountToConceptSearch() {
        // This test will ensure modifications made to the conceptSearch will be reflected in the legacy search result.
        // They use near equivalent queries and updates made to one should be made to the other.
        List<SearchResult> searchResults = subject.getLegacySearchResults(new Filter(List.of(), "", List.of()), Pageable.unpaged());
        List<Concept> concepts = conceptService.getConcepts(new Filter(List.of(), "", List.of()), Pageable.unpaged());

        Assertions.assertEquals(searchResults.size(), concepts.size());
    }

}
