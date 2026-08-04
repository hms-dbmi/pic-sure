package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.gateway.docs.OpenApiDocuments;
import edu.harvard.hms.dbmi.avillach.gateway.docs.SwaggerUiAssets;

/**
 * The same-origin API docs console: {@code GET /openapi[/{document}]} serves the six committed contract documents, and
 * {@code GET /swagger-ui} serves a bundled Swagger UI over them. Both prefixes were already reserved on the gateway's unauthenticated
 * allow-list ({@code picsure.gateway.security.allow-list-prefixes} in {@code application.yml}) since the WAR era; this is the feature they
 * were reserved for, so the console is reachable without a token in every environment that leaves it enabled.
 *
 * <p><b>Kill switch.</b> {@code picsure.gateway.docs.enabled} (default true, env {@code GATEWAY_DOCS_ENABLED}). Set it false and this whole
 * configuration -- routes, document cache and viewer alike -- is never created, so every path under both prefixes 404s: the gateway has no
 * catch-all route. FISMA-constrained or production environments that must not publish an API console set it there, with no image change.
 *
 * <p><b>Precedence.</b> Both {@code RouterFunction}s are ordered {@code HIGHEST_PRECEDENCE}, matching {@code HealthConfig}'s
 * {@code /system/status} route, so they resolve ahead of the Spring Cloud Gateway proxy routes (which carry no explicit order and so sit at
 * {@code LOWEST_PRECEDENCE}). The prefixes do not overlap any configured route, so this is belt-and-braces rather than a live conflict.
 */
@Configuration
@ConditionalOnProperty(name = "picsure.gateway.docs.enabled", havingValue = "true", matchIfMissing = true)
public class DocsConfig {

    @Bean
    public OpenApiDocuments openApiDocuments(ObjectMapper json) {
        return new OpenApiDocuments(json);
    }

    @Bean
    public SwaggerUiAssets swaggerUiAssets() {
        return new SwaggerUiAssets();
    }

    /**
     * {@code GET /openapi} (the index) and {@code GET /openapi/{document}} (one document, servers rewritten for public ingress). Unknown
     * document names 404 -- {@code OpenApiDocuments} resolves them against a closed six-entry map, never against the filesystem, so the
     * route has no traversal surface to defend.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> openApiDocumentRoute(OpenApiDocuments documents) {
        return RouterFunctions.route().GET("/openapi", request -> json(documents.index())).GET("/openapi/{document}", request -> {
            return documents.document(request.pathVariable("document")).map(DocsConfig::json)
                .orElseGet(() -> ServerResponse.notFound().build());
        }).build();
    }

    /**
     * {@code GET /swagger-ui} (the checked-in initializer) and {@code GET /swagger-ui/{asset}} (allow-listed webjar files).
     *
     * <p><b>Why the page lives at the slash-less path.</b> httpd mounts the gateway under {@code /picsure}, which the gateway never sees,
     * so the page cannot write absolute asset URLs -- everything it loads is relative and must resolve under {@code /picsure}. From
     * {@code /picsure/swagger-ui} a relative {@code swagger-ui/swagger-ui.css} resolves to {@code /picsure/swagger-ui/swagger-ui.css} and
     * {@code openapi} resolves to {@code /picsure/openapi}, both correct; from a trailing-slash URL the same references would resolve one
     * segment too deep. So {@code /swagger-ui/} is answered with a RELATIVE redirect back to the canonical form -- relative because an
     * absolute {@code Location} would drop the {@code /picsure} prefix the gateway cannot know about.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> swaggerUiRoute(SwaggerUiAssets assets) {
        return RouterFunctions.route()
            .GET("/swagger-ui", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(assets.initializer()))
            .GET("/swagger-ui/", request -> ServerResponse.status(302).header("Location", "../swagger-ui").build())
            .GET(
                "/swagger-ui/{asset}",
                request -> assets.asset(request.pathVariable("asset"))
                    .map(asset -> ServerResponse.ok().contentType(asset.contentType()).body(asset.resource()))
                    .orElseGet(() -> ServerResponse.notFound().build())
            ).build();
    }

    private static ServerResponse json(String body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
