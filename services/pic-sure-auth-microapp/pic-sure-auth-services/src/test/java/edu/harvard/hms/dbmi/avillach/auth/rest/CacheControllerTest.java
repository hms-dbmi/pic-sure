package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /cache} returns the cache names as a bare array. {@code GET /cache/{cacheName}} returns the cache's name beside its entries,
 * and the entries are one JSON object of cache keys to cached values.
 */
class CacheControllerTest {

    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
    private final MockMvc mockMvc = FrozenWire.mockMvc(new CacheController(cacheManager));

    @Test
    void cacheNamesAreABareArrayOfStrings() throws Exception {
        cacheManager.getCache("sessions");

        mockMvc.perform(get("/cache")).andExpect(status().isOk()).andExpect(content().string("[\"sessions\"]"));
    }

    @Test
    void cacheContentsCarryTheNameAndTheEntriesTheEndpointUsedToReturnBare() throws Exception {
        Cache cache = cacheManager.getCache("mergedRulesCache");
        cache.put("fence|12345", new LinkedHashSet<>(List.of(AdminFixtures.accessRule("AR_ONLY_SEARCH"))));
        String entriesAsReturnedBefore = FrozenWire.MAPPER.writeValueAsString(cache.getNativeCache());

        mockMvc.perform(get("/cache/{cacheName}", "mergedRulesCache")).andExpect(status().isOk())
            .andExpect(content().string("{\"name\":\"mergedRulesCache\",\"entries\":" + entriesAsReturnedBefore + "}"));
    }

    @Test
    void anEmptyCacheHasNoEntries() throws Exception {
        mockMvc.perform(get("/cache/{cacheName}", "preProcessedAccessRules")).andExpect(status().isOk())
            .andExpect(content().string("{\"name\":\"preProcessedAccessRules\",\"entries\":{}}"));
    }

    @Test
    void aCacheWhoseStoreIsNotAMapIs500() throws Exception {
        CacheManager foreignManager = mock(CacheManager.class);
        Cache foreignCache = mock(Cache.class);
        when(foreignManager.getCache("foreign")).thenReturn(foreignCache);
        when(foreignCache.getNativeCache()).thenReturn("not a map");

        FrozenWire.mockMvc(new CacheController(foreignManager)).perform(get("/cache/{cacheName}", "foreign"))
            .andExpect(status().isInternalServerError()).andExpect(
                content().string(
                    "{\"message\":\"An error occurred while processing your request\",\"content\":\"Cache store is not a map: foreign\"}"
                )
            );
    }
}
