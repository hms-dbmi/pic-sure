package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;

/**
 * Supplies the open backend's study-consents concept paths. The list is dataset-static, so it is cached (Spring {@code @Cacheable}, backed
 * by the default {@code ConcurrentMapCacheManager}) to spare one HPDS {@code /search} per open count, and evicted on a schedule so a dataset
 * reload is picked up within {@code aggregate.study-consents-cache-ttl-sec}. Set {@code spring.cache.type=none} to disable the cache.
 */
@Component
public class StudyConsentPathProvider {

    static final String CACHE = "studyConsentPaths";
    private static final String STUDIES_CONSENTS_PATH = "\\_studies_consents\\";

    private final AggregateBackendClient backend;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StudyConsentPathProvider(AggregateBackendClient backend) {
        this.backend = backend;
    }

    /** Lists the study-consents concept paths on the open backend, in the order HPDS returns them. Cached between requests. */
    @Cacheable(CACHE)
    public List<String> studyConsentPaths() {
        SearchResults consentResults = backend.search(new GeneralQueryRequest().setQuery(STUDIES_CONSENTS_PATH));
        LinkedHashMap<String, Object> resultsMap = objectMapper.convertValue(consentResults.getResults(), new TypeReference<>() {});
        LinkedHashMap<String, Object> phenotypes = objectMapper.convertValue(resultsMap.get("phenotypes"), new TypeReference<>() {});
        return List.copyOf(phenotypes.keySet());
    }

    /** Drops the cached list so the next lookup re-fetches it, bounding staleness after a dataset reload. */
    @Scheduled(fixedRateString = "${aggregate.study-consents-cache-ttl-sec:300}", timeUnit = TimeUnit.SECONDS)
    @CacheEvict(value = CACHE, allEntries = true)
    public void evictStudyConsentPaths() {
    }
}
