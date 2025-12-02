package edu.harvard.dbmi.avillach.dictionary.util;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Value("${cache.duration-minutes:60}")
    private int cacheDurationMinutes;

    @Value("${cache.maximum-size:5000}")
    private int cacheMaximumSize;

    @Bean
    public Caffeine caffeineConfig() {
        return Caffeine.newBuilder().expireAfterAccess(cacheDurationMinutes, TimeUnit.MINUTES).maximumSize(cacheMaximumSize);
    }

    @Bean
    public CacheManager cacheManager(Caffeine caffeine) {
        CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
        caffeineCacheManager.setCaffeine(caffeine);
        return caffeineCacheManager;
    }
}
