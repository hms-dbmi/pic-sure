package edu.harvard.hms.dbmi.avillach.ai.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link CallerContextArgumentResolver} so {@code ChatController} can declare a {@code CallerContext} method parameter and
 * receive the caller's bearer JWT and identity, with a 401 raised before the handler body runs if the header is missing.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerContextArgumentResolver());
    }
}
