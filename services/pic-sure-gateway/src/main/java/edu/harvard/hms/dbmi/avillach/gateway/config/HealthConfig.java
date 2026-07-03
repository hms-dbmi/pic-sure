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
 * Registers the Task-9 deep-health pieces as Spring beans so the {@code /system/status} legacy text controller (Task 10) and the
 * {@code /actuator/health} composite (Task 12) can consume them.
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
     * Exposes {@code GET /system/status} ahead of the Phase-1 catch-all gateway route. The gateway's own composite {@code RouterFunction}
     * bean carries no explicit {@code @Order} (so it defaults to {@code Ordered.LOWEST_PRECEDENCE}); ordering this bean at
     * {@code HIGHEST_PRECEDENCE} guarantees it is tried first within {@code RouterFunctionMapping}, so the request never reaches the
     * WildFly catch-all. See {@link SystemStatusController}'s class Javadoc for the full precedence chain.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> systemStatusRoute(SystemStatusController controller) {
        return RouterFunctions.route(
            RequestPredicates.GET("/system/status"),
            request -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body(controller.status())
        );
    }
}
