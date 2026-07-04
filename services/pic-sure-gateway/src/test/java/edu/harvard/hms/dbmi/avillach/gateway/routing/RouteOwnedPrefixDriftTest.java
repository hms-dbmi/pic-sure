package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import edu.harvard.hms.dbmi.avillach.gateway.config.RouteSurfaces;

/**
 * Drift guard: every configured NON-catch-all route must be covered by the default gateway-owned prefixes
 * ({@code RouteSurfaceProperties.DEFAULT_OWNED_PREFIXES}, via {@link RouteSurfaces#withDefaults()}). Binds
 * {@code spring.cloud.gateway.server.webmvc.routes} straight from the environment (same Binder technique as {@code NoRegistryRouteTest}) so
 * that adding a route to {@code application.yml} without extending the owned-prefixes default fails THIS test -- the owned-vs-catch-all
 * classification that drives the OBSERVE split can never silently drift out of sync with the route table.
 */
@SpringBootTest
class RouteOwnedPrefixDriftTest {

    @Autowired
    Environment env;

    private final RouteSurfaces surfaces = RouteSurfaces.withDefaults();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> configuredRoutes() {
        return Binder.get(env)
            .bind("spring.cloud.gateway.server.webmvc.routes", Bindable.listOf((Class<Map<String, Object>>) (Class<?>) Map.class))
            .orElse(List.of());
    }

    @Test
    void everyNonCatchAllRouteIsCoveredByDefaultOwnedPrefixes() {
        List<Map<String, Object>> routes = configuredRoutes();
        assertThat(routes).as("routes must be bound from application.yml").isNotEmpty();

        boolean sawCatchAll = false;
        for (Map<String, Object> route : routes) {
            String base = ownedBaseOf(route);
            if (base.isEmpty()) {
                sawCatchAll = true; // the /** legacy WildFly catch-all -- the one route that is NOT owned
                continue;
            }
            assertThat(surfaces.isOwned(base)).as("owned prefix covers route path %s (id=%s)", base, route.get("id")).isTrue();
            assertThat(surfaces.isOwned(base + "/sub/path")).as("owned prefix covers under %s (id=%s)", base, route.get("id")).isTrue();
        }
        assertThat(sawCatchAll).as("expected exactly the /** catch-all to be the sole non-owned route").isTrue();
    }

    /** The route's Path predicate stripped of its trailing {@code /**} (or {@code /*}); empty string for the {@code /**} catch-all. */
    private static String ownedBaseOf(Map<String, Object> route) {
        String path = pathPredicate(route);
        assertThat(path).as("route %s must declare a Path predicate", route.get("id")).isNotNull();
        if (path.endsWith("/**")) {
            return path.substring(0, path.length() - "/**".length());
        }
        if (path.endsWith("/*")) {
            return path.substring(0, path.length() - "/*".length());
        }
        return path;
    }

    private static String pathPredicate(Map<String, Object> route) {
        // Binding the shortcut predicate list from application.yml yields either a List or an index-keyed Map
        // (e.g. {0=Path=/logging/**}); handle both by scanning every value for the Path predicate.
        Object predicates = route.get("predicates");
        Collection<?> entries = switch (predicates) {
            case List<?> list -> list;
            case Map<?, ?> map -> map.values();
            case null, default -> List.of();
        };
        for (Object predicate : entries) {
            String s = String.valueOf(predicate);
            int idx = s.indexOf("Path=");
            if (idx >= 0) {
                String value = s.substring(idx + "Path=".length());
                int comma = value.indexOf(',');
                return (comma >= 0 ? value.substring(0, comma) : value).trim();
            }
        }
        return null;
    }
}
