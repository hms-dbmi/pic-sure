package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring caching and scheduling for the service. {@code StudyConsentPathProvider}'s {@code @Cacheable} lookup uses Spring Boot's
 * default {@code ConcurrentMapCacheManager} (as in PSAMA), and its scheduled {@code @CacheEvict} bounds staleness after a dataset reload.
 * Kept separate from {@code AggregateConfig} so the narrow slice tests that import that config do not need a {@code CacheManager}.
 */
@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {
}
