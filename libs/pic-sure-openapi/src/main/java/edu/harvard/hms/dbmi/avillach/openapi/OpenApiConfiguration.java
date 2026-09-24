package edu.harvard.hms.dbmi.avillach.openapi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Publishes the one {@link OpenAPI} bean every PIC-SURE service shares: an info block naming the service and its version, and a bearer
 * token security scheme applied to every operation so Swagger UI offers the Authorize button and sends the token on "try it out". The title
 * and version come from {@code picsure.openapi.title} and {@code picsure.openapi.version} when set, otherwise from Boot's
 * {@link BuildProperties} (artifact id and version), otherwise from {@code spring.application.name} and the literal {@code unversioned}.
 * Servers, tags, and path filtering are deliberately absent: the public ingress is the gateway's knowledge, not the service's.
 */
@AutoConfiguration(after = ProjectInfoAutoConfiguration.class)
@ConditionalOnClass(OpenAPI.class)
public class OpenApiConfiguration {

    public static final String BEARER_SCHEME = "bearerAuth";

    static final String TITLE_PROPERTY = "picsure.openapi.title";
    static final String VERSION_PROPERTY = "picsure.openapi.version";
    static final String FALLBACK_TITLE = "application";
    static final String FALLBACK_VERSION = "unversioned";

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI picsureOpenApi(Environment environment, ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String title = firstNonBlank(
            environment.getProperty(TITLE_PROPERTY), build == null ? null : build.getArtifact(),
            environment.getProperty("spring.application.name"), FALLBACK_TITLE
        );
        String version =
            firstNonBlank(environment.getProperty(VERSION_PROPERTY), build == null ? null : build.getVersion(), FALLBACK_VERSION);
        SecurityScheme bearer = new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT");
        return new OpenAPI().info(new Info().title(title).version(version))
            .components(new Components().addSecuritySchemes(BEARER_SCHEME, bearer))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        throw new IllegalStateException("every candidate value was blank");
    }
}
