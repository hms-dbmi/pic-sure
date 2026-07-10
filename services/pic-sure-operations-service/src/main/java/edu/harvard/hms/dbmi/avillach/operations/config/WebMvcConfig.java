package edu.harvard.hms.dbmi.avillach.operations.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link GatewayUserArgumentResolver} so later controllers (config/dataset/query) can declare a
 * {@code edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} method parameter and receive the caller's identity, re-derived from
 * the gateway's {@code X-User-*} headers.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new GatewayUserArgumentResolver());
    }
}
