package edu.harvard.hms.dbmi.avillach.auth.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/**
 * Provides the {@link PublicRoutes} that {@link SecurityConfig} permits without a token. A malformed or overridden entry fails here and
 * stops the context from starting.
 */
@Configuration
public class PublicRoutesConfiguration {

    static final String PACKAGED_PROPERTIES = "application.properties";

    /**
     * Loads the shipped routes from the packaged {@code application.properties} and the additional routes from the environment.
     *
     * @param environment the running application's environment
     * @return every route served without a token
     * @throws IOException if the packaged properties file cannot be read
     */
    @Bean
    public PublicRoutes publicRoutes(Environment environment) throws IOException {
        return PublicRoutes.load(new ClassPathResource(PACKAGED_PROPERTIES), environment);
    }
}
