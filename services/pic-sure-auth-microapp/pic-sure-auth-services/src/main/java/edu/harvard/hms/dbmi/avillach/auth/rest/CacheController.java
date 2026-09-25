package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@Tag(name = "Cache", description = "Cache inspection, enabled only when app.cache.inspect.enabled is true")
@RestController
@ConditionalOnExpression("${app.cache.inspect.enabled:false}")
@RequestMapping("/cache")
public class CacheController {

    private final CacheManager cacheManager;

    @Autowired
    public CacheController(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Operation(summary = "List cache names")
    @ApiResponse(responseCode = "200", description = "Names of every configured cache")
    @AuditEvent(type = "OTHER", action = "cache.list")
    @GetMapping
    public Collection<String> getCacheNames() {
        return cacheManager.getCacheNames();
    }

    @Operation(summary = "Dump one cache")
    @ApiResponse(responseCode = "200", description = "The cache's native contents")
    @AuditEvent(type = "OTHER", action = "cache.read")
    @GetMapping("/{cacheName}")
    public Object getCache(@PathVariable("cacheName") String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new IllegalArgumentException("Cache not found: " + cacheName);
        }

        return cache.getNativeCache();
    }


}
