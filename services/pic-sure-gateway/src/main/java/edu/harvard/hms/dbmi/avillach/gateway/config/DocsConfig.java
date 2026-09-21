package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.gateway.docs.DocsHandlers;
import edu.harvard.hms.dbmi.avillach.gateway.docs.DocsProperties;
import edu.harvard.hms.dbmi.avillach.gateway.docs.OpenApiDocumentFetcher;
import edu.harvard.hms.dbmi.avillach.gateway.docs.SwaggerUiAssets;
import edu.harvard.hms.dbmi.avillach.gateway.docs.SwaggerUiHandlers;
import edu.harvard.hms.dbmi.avillach.gateway.health.DownstreamHealthProperties;

/**
 * Wires the docs console behind the {@code GATEWAY_DOCS_ENABLED} kill switch ({@code picsure.gateway.docs.enabled}, default true). When the
 * switch is off none of these beans exist and every {@code /openapi} and {@code /swagger-ui} path falls through to the gateway's 404, since
 * it has no catch-all route. Both router functions sit at highest precedence, the {@link HealthConfig} pattern, so they are tried before
 * the proxy routes.
 */
@Configuration
@ConditionalOnProperty(prefix = "picsure.gateway.docs", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(DocsProperties.class)
public class DocsConfig {

    @Bean
    public OpenApiDocumentFetcher openApiDocumentFetcher(DownstreamHealthProperties health, ObjectMapper json) {
        return OpenApiDocumentFetcher.withTimeouts(health.connectTimeoutMs(), health.readTimeoutMs(), json);
    }

    @Bean
    public DocsHandlers docsHandlers(DocsProperties props, OpenApiDocumentFetcher fetcher, ObjectMapper json) {
        return new DocsHandlers(props, fetcher, json);
    }

    @Bean
    public SwaggerUiAssets swaggerUiAssets() {
        return new SwaggerUiAssets();
    }

    @Bean
    public SwaggerUiHandlers swaggerUiHandlers(SwaggerUiAssets assets, ObjectMapper json) {
        return new SwaggerUiHandlers(assets, json);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> openApiDocumentRoutes(DocsHandlers handlers) {
        return RouterFunctions.route(RequestPredicates.GET("/openapi"), handlers::index)
            .andRoute(RequestPredicates.GET("/openapi/{name}"), handlers::document);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> swaggerUiRoutes(SwaggerUiHandlers handlers) {
        return RouterFunctions.route(RequestPredicates.GET("/swagger-ui"), handlers::viewer)
            .andRoute(RequestPredicates.GET("/swagger-ui/"), handlers::viewerTrailingSlash)
            .andRoute(RequestPredicates.GET("/swagger-ui/{asset}"), handlers::asset);
    }
}
