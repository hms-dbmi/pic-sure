package edu.harvard.hms.dbmi.avillach.auth.model.response;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.cache.Cache;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code GET /cache/{cacheName}}, which previously returned {@code Object} -- literally whichever native structure the
 * configured {@code CacheManager} happens to hold ({@code ConcurrentHashMap} for the in-process manager PSAMA uses), with no describable
 * schema and no guarantee it serializes at all under a different provider.
 *
 * <p>This is an operator debug endpoint gated off by default ({@code app.cache.inspect.enabled=false}), so the shape is chosen for
 * legibility rather than compatibility: cache name, entry count, and the entries when the native cache is a {@link Map} we can walk. Keys
 * are rendered with {@code toString()} because {@code CustomKeyGenerator} produces plain subject strings but nothing constrains a future
 * generator to do the same.
 */
@Schema(description = "Inspection view of one named cache")
public record CacheContents(
    @Schema(description = "The cache's name") String cacheName, @Schema(description = "Number of entries") int size,
    @Schema(description = "The cached entries, empty when the native cache is not a walkable map") Map<String, Object> entries
) {

    public static CacheContents of(String cacheName, Cache cache) {
        Object nativeCache = cache.getNativeCache();
        if (!(nativeCache instanceof Map<?, ?> map)) {
            return new CacheContents(cacheName, 0, Map.of());
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        map.forEach((key, value) -> entries.put(String.valueOf(key), value));
        return new CacheContents(cacheName, entries.size(), entries);
    }
}
