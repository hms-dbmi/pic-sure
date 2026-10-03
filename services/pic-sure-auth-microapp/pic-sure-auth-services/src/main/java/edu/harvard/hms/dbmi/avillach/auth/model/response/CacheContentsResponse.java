package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The entries of one cache, as {@code GET /cache/{cacheName}} returns them.
 *
 * @param name the cache name
 * @param entries the cached values by key
 */
@Schema(description = "The entries one cache holds at the moment it is read.")
public record CacheContentsResponse(
    @Schema(description = "Name of the cache.", example = "mergedRulesCache", requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(
        description = "The cached values. Each key is a cache key written as text, which for the sessions, mergedRulesCache and "
            + "preProcessedAccessRules caches is a user's subject. Each value is whatever the cache stores under that key.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) Map<String, Object> entries
) {

    /**
     * Reads the entries out of a cache's backing store.
     *
     * @param name the cache name
     * @param nativeCache the store {@code Cache.getNativeCache()} returns
     * @return the cache's entries, keyed by the text form of each cache key
     * @throws IllegalStateException if the store is not a map, which no cache manager this service configures produces
     */
    public static CacheContentsResponse of(String name, Object nativeCache) {
        if (!(nativeCache instanceof Map<?, ?> store)) {
            throw new IllegalStateException("Cache store is not a map: " + name);
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        store.forEach((key, value) -> entries.put(String.valueOf(key), value));
        return new CacheContentsResponse(name, entries);
    }
}
