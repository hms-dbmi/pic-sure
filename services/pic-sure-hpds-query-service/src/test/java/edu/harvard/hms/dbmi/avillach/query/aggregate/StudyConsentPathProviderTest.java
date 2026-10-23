package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import edu.harvard.dbmi.avillach.domain.SearchResults;

/**
 * Pins the {@code @Cacheable} behavior of {@link StudyConsentPathProvider}: the dataset-static path list is fetched once and reused, and
 * {@link StudyConsentPathProvider#evictStudyConsentPaths()} forces the next lookup to re-fetch. The scheduled trigger on that method is not
 * exercised here; only its eviction is.
 */
@SpringBootTest(classes = StudyConsentPathProviderTest.CacheTestConfig.class)
class StudyConsentPathProviderTest {

    @EnableCaching
    @Configuration
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(StudyConsentPathProvider.CACHE);
        }

        @Bean
        StudyConsentPathProvider studyConsentPathProvider(AggregateBackendClient backend) {
            return new StudyConsentPathProvider(backend);
        }
    }

    @MockitoBean
    private AggregateBackendClient backend;

    @Autowired
    private StudyConsentPathProvider provider;

    @BeforeEach
    void clearCache() {
        provider.evictStudyConsentPaths(); // the cache is shared across test methods in one context
    }

    private SearchResults consentsSearch() {
        Map<String, Object> phenotypes = new LinkedHashMap<>();
        phenotypes.put("\\study\\a\\consent\\", Map.of());
        phenotypes.put("\\study\\b\\consent\\", Map.of());
        return new SearchResults().setResults(Map.of("phenotypes", phenotypes));
    }

    @Test
    void fetchesOnceAndCachesAcrossCalls() {
        when(backend.search(any())).thenReturn(consentsSearch());

        assertThat(provider.studyConsentPaths()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
        assertThat(provider.studyConsentPaths()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");

        verify(backend, times(1)).search(any());
    }

    @Test
    void evictionForcesARefetch() {
        when(backend.search(any())).thenReturn(consentsSearch());

        provider.studyConsentPaths();
        provider.evictStudyConsentPaths();
        provider.studyConsentPaths();

        verify(backend, times(2)).search(any());
    }
}
