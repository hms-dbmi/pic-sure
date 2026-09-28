package edu.harvard.hms.dbmi.avillach.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import edu.harvard.hms.dbmi.avillach.openapi.PublicEndpoint;
import edu.harvard.hms.dbmi.avillach.openapi.PublicEndpoint.Access;

/**
 * Keeps the handlers marked {@code @PublicEndpoint(ANONYMOUS)} and {@code security.public-routes.shipped} in step, in both directions. Every
 * shipped route must be served by an anonymous handler, unless it is one of the routes listed here that no controller serves, and every
 * verb and path of an anonymous handler must fall under a shipped route. The annotation grants nothing, so an anonymous handler outside the
 * shipped routes would still demand a token, and a shipped route without one would be public with nobody having said so at the handler.
 * Handler mappings and shipped patterns are both relative to the {@code /auth} context path, the same way {@code PublicRoutesBindingTest}
 * sends its requests, so neither carries the prefix.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.datasource.url=jdbc:h2:mem:psama-openapi;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,VALUE,KEY",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "APPLICATION_CLIENT_SECRET=openapi-test-placeholder-secret", "management.endpoints.web.exposure.include=none",
        "app.cache.inspect.enabled=true"}
)
class PublicEndpointRoutesTest {

    private static final String PSAMA_PACKAGE = "edu.harvard.hms.dbmi.avillach.auth.";

    private static final Map<String, String> SERVED_WITHOUT_A_CONTROLLER = Map.of(
        "/actuator/health", "Spring Boot actuator serves the health probe.",
        "/actuator/info", "Spring Boot actuator serves the info probe.",
        "/v3/api-docs/**", "springdoc serves the OpenAPI document.",
        "/logout", "Spring Security's LogoutFilter answers it before any controller.",
        "/authentication", "The bare prefix of /authentication/{idpProvider}; no handler maps it."
    );

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    @Autowired
    private PublicRoutes publicRoutes;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyShippedRouteIsServedByAnAnonymousHandlerOrListedAsServedWithoutOne() {
        List<String> unmatched = new ArrayList<>();
        for (PublicRoute route : publicRoutes.shipped()) {
            PathPattern pattern = PARSER.parse(route.pattern());
            boolean served = anonymousMappings().stream().anyMatch(mapping -> pattern.matches(PathContainer.parsePath(mapping.samplePath())));
            if (!served && !SERVED_WITHOUT_A_CONTROLLER.containsKey(route.pattern())) {
                unmatched.add(route.pattern());
            }
        }

        assertThat(unmatched).as("shipped routes with no @PublicEndpoint(ANONYMOUS) handler").isEmpty();
    }

    @Test
    void routesListedAsServedWithoutAControllerHaveNoPsamaHandler() {
        List<String> nowServed = new ArrayList<>();
        for (String route : SERVED_WITHOUT_A_CONTROLLER.keySet()) {
            PathPattern pattern = PARSER.parse(route);
            psamaMappings().stream().filter(mapping -> pattern.matches(PathContainer.parsePath(mapping.samplePath())))
                .forEach(mapping -> nowServed.add(route + " is served by " + mapping.handler()));
        }

        assertThat(nowServed).as("routes listed as served without a controller that now have one").isEmpty();
    }

    @Test
    void everyAnonymousHandlerFallsUnderAShippedRoute() {
        List<String> uncovered = new ArrayList<>();
        for (Mapping mapping : anonymousMappings()) {
            boolean covered = publicRoutes.shipped().stream().anyMatch(route -> covers(route, mapping));
            if (!covered) {
                String verbs = mapping.methods().isEmpty() ? "any verb" : String.join(",", mapping.methods());
                uncovered.add(mapping.handler() + " " + verbs + " " + mapping.pattern());
            }
        }

        assertThat(uncovered).as("@PublicEndpoint(ANONYMOUS) handlers outside security.public-routes.shipped").isEmpty();
    }

    private static boolean covers(PublicRoute route, Mapping mapping) {
        boolean verbs = route.methods().isEmpty() || (!mapping.methods().isEmpty() && route.methods().containsAll(mapping.methods()));
        return verbs && PARSER.parse(route.pattern()).matches(PathContainer.parsePath(mapping.samplePath()));
    }

    private List<Mapping> anonymousMappings() {
        return psamaMappings().stream().filter(mapping -> mapping.access() == Access.ANONYMOUS).toList();
    }

    private List<Mapping> psamaMappings() {
        List<Mapping> mappings = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            if (!handlerMethod.getBeanType().getName().startsWith(PSAMA_PACKAGE)) {
                continue;
            }
            String handler = handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
            PublicEndpoint publicEndpoint = handlerMethod.getMethodAnnotation(PublicEndpoint.class);
            Access access = publicEndpoint == null ? null : publicEndpoint.value();
            Set<String> methods =
                entry.getKey().getMethodsCondition().getMethods().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
            for (String pattern : entry.getKey().getPatternValues()) {
                mappings.add(new Mapping(handler, access, methods, pattern));
            }
        }
        return mappings;
    }

    /**
     * One verb set and path pattern a handler is mapped to.
     *
     * @param handler the handler as {@code Controller#method}
     * @param access the level its {@code @PublicEndpoint} declares, or null when it carries none
     * @param methods the HTTP methods it is mapped to; empty means every method
     * @param pattern its path pattern relative to the context path, such as {@code /authentication/{idpProvider}}
     */
    private record Mapping(String handler, Access access, Set<String> methods, String pattern) {

        /**
         * @return the pattern with each path variable replaced by a literal segment, so a route pattern can be matched against it
         */
        String samplePath() {
            return pattern.replaceAll("\\{[^}]+}", "x");
        }
    }
}
