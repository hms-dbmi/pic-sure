package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring caching for the service. {@code StudyConsentPathProvider}'s {@code @Cacheable} lookup uses Spring Boot's default
 * {@code ConcurrentMapCacheManager} (as in PSAMA). Kept separate from {@code AggregateConfig} so the narrow slice tests that import that
 * config do not need a {@code CacheManager}.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
