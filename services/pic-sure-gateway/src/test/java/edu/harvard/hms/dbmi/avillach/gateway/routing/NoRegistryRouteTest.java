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
 * Spec 3.3 + review P-mi5: the resource registry is removed. Assert on the configured route IDS
 * (behavior-pinning, not a weak bean-name check) — the gateway exposes NO {@code /info/resources} or
 * {@code /resource} route. Those paths fall through to the WildFly catch-all until decommission.
 *
 * <p>Phase-3 configured routes today: {@code logging} (Task 1) + {@code legacy-wildfly-catchall} (Phase 1).
 * {@code dictionary}/{@code uploader} are deferred until the PSAMA access-rule migration, so they are not yet
 * present — the load-bearing assertion is that no registry id ever appears.
 */
@SpringBootTest
class NoRegistryRouteTest {

    @Autowired
    Environment env;

    @SuppressWarnings("unchecked")
    private Set<String> configuredRouteIds() {
        List<Map<String, Object>> routes = Binder.get(env)
            .bind("spring.cloud.gateway.server.webmvc.routes",
                Bindable.listOf((Class<Map<String, Object>>) (Class<?>) Map.class))
            .orElse(List.of());
        return routes.stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toSet());
    }

    @Test
    void exposesOnlyTheExpectedRouteIdsAndNoRegistryRoute() {
        Set<String> ids = configuredRouteIds();
        assertThat(ids).containsExactlyInAnyOrder("logging", "legacy-wildfly-catchall");
        assertThat(ids).noneMatch(id -> {
            String lower = id.toLowerCase();
            return lower.contains("resource") || lower.contains("info-resources");
        });
    }
}
