package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.auth.model.response.CacheContents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/** Operator cache inspection, gated off by default ({@code app.cache.inspect.enabled}). */
@RestController
@ConditionalOnExpression("${app.cache.inspect.enabled:false}")
@RequestMapping("/cache")
public class CacheController {

    private final CacheManager cacheManager;

    @Autowired
    public CacheController(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @AuditEvent(type = "OTHER", action = "cache.list")
    @GetMapping
    public Collection<String> getCacheNames() {
        return cacheManager.getCacheNames();
    }

    /**
     * Was {@code Object} -- whichever native structure the configured cache provider happened to hold, with no schema and no guarantee it
     * serialized at all. See {@link CacheContents}.
     */
    @AuditEvent(type = "OTHER", action = "cache.read")
    @GetMapping("/{cacheName}")
    public CacheContents getCache(@PathVariable("cacheName") String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new IllegalArgumentException("Cache not found: " + cacheName);
        }

        return CacheContents.of(cacheName, cache);
    }


}
