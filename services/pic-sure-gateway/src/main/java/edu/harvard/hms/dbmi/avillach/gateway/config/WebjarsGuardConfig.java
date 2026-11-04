package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Closes Boot's default {@code /webjars/**} static resource mapping, which the {@code org.webjars:swagger-ui} dependency (added so the docs
 * console can serve its own {@code /swagger-ui/*} assets) would otherwise expose in full: every file inside that jar, not the six the
 * console allow-lists, including {@code index.html}, {@code oauth2-redirect.html}, {@code swagger-initializer.js}, and source maps.
 * Registered unconditionally, independent of {@code picsure.gateway.docs.enabled}, because the webjar stays on the classpath and Boot's
 * static mapping stays active even when the docs console itself is switched off.
 */
@Configuration
public class WebjarsGuardConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> webjarsCatchAllGuard() {
        return RouterFunctions.route(
            RequestPredicates.path("/webjars").or(RequestPredicates.path("/webjars/**")), request -> ServerResponse.notFound().build()
        );
    }
}
