package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * The resource registry is removed. Assert on the configured route IDS (behavior-pinning, not a weak bean-name check) — the gateway exposes
 * NO {@code /info/resources} or {@code /resource} route. Those paths are unmatched and 404.
 *
 * <p>Configured routes: {@code logging}, {@code dictionary}, {@code visualization}, plus {@code hpds}, {@code operations} — verbatim routes
 * to the query-service / operations-service. The load-bearing assertion is that no registry id ever appears.
 */
@SpringBootTest
class NoRegistryRouteTest {

    @Autowired
    Environment env;

    @SuppressWarnings("unchecked")
    private Set<String> configuredRouteIds() {
        List<Map<String, Object>> routes = Binder.get(env)
            .bind("spring.cloud.gateway.server.webmvc.routes", Bindable.listOf((Class<Map<String, Object>>) (Class<?>) Map.class))
            .orElse(List.of());
        return routes.stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toSet());
    }

    @Test
    void exposesOnlyTheExpectedRouteIdsAndNoRegistryRoute() {
        Set<String> ids = configuredRouteIds();
        assertThat(ids).containsExactlyInAnyOrder("logging", "dictionary", "visualization", "hpds", "operations");
        assertThat(ids).doesNotContain("uploader");
        assertThat(ids).noneMatch(id -> {
            String lower = id.toLowerCase();
            return lower.contains("resource") || lower.contains("info-resources");
        });
    }
}
