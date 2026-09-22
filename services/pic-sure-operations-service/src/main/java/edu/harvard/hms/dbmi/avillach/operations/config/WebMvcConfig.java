package edu.harvard.hms.dbmi.avillach.operations.config;

import java.util.List;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

/**
 * Registers {@link GatewayUserArgumentResolver} so later controllers (config/dataset/query) can declare a
 * {@code edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser} method parameter and receive the caller's identity, re-derived from
 * the gateway's {@code X-User-*} headers.
 *
 * <p>The same registration tells springdoc to skip that type. A handler parameter with no binding annotation is client input as far as
 * springdoc is concerned, so without this it publishes {@code GatewayUser} as a required {@code user} query parameter on every operation
 * that takes one, along with a schema of the identity fields. Nothing reads those query values; the resolver reads headers.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(GatewayUser.class);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new GatewayUserArgumentResolver());
    }
}
