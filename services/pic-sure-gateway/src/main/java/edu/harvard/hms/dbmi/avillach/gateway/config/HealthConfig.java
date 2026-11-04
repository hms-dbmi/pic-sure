package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import edu.harvard.hms.dbmi.avillach.gateway.health.DownstreamHealthProperties;
import edu.harvard.hms.dbmi.avillach.gateway.health.SystemHealthService;
import edu.harvard.hms.dbmi.avillach.gateway.health.SystemStatusController;

/**
 * Registers deep-health components for the {@code /system/status} text endpoint and {@code /actuator/health} composite.
 */
@Configuration
@EnableConfigurationProperties(DownstreamHealthProperties.class)
public class HealthConfig {

    @Bean
    public SystemHealthService systemHealthService(DownstreamHealthProperties props) {
        return new SystemHealthService(props);
    }

    @Bean
    public SystemStatusController systemStatusController(
        SystemHealthService systemHealthService, @Value("${picsure.gateway.health.status-window-ms:60000}") long statusWindowMs
    ) {
        return new SystemStatusController(systemHealthService, statusWindowMs);
    }

    /**
     * Exposes {@code GET /system/status} at highest precedence. The gateway's own composite {@code RouterFunction} bean carries no explicit
     * {@code @Order} (so it defaults to {@code Ordered.LOWEST_PRECEDENCE}); ordering this bean at {@code HIGHEST_PRECEDENCE} guarantees it
     * is tried first within {@code RouterFunctionMapping}. See {@link SystemStatusController}'s class Javadoc for the full precedence
     * chain.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> systemStatusRoute(SystemStatusController controller) {
        return RouterFunctions.route(
            RequestPredicates.GET("/system/status"),
            request -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body(controller.status())
        );
    }

    /**
     * Returns a clean 404 for {@code /actuator/**} when actuator is disabled (the default: no {@code PICSURE_ACTUATOR_EXPOSURE} set).
     * Actuator endpoints are served by {@code WebMvcEndpointHandlerMapping} (order -100, ahead of {@code RouterFunctionMapping}), so
     * whenever an endpoint IS exposed this route is never consulted for it; but an UNexposed {@code /actuator/*} path would otherwise fall
     * through unhandled. Ordering at {@code HIGHEST_PRECEDENCE} ensures this guard is reached first, so unexposed actuator endpoints are
     * never reachable unless explicitly enabled.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> actuatorCatchAllGuard() {
        return RouterFunctions.route(
            RequestPredicates.path("/actuator").or(RequestPredicates.path("/actuator/**")), request -> ServerResponse.notFound().build()
        );
    }
}
