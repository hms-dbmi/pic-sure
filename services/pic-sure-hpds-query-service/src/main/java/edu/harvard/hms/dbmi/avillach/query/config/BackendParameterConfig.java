package edu.harvard.hms.dbmi.avillach.query.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;

/**
 * Registers the {@code {backend}} path parameter once under {@code components/parameters}, so every {@code /hpds/{backend}} operation
 * references one definition instead of repeating it.
 */
@Configuration
public class BackendParameterConfig {

    public static final String BACKEND_PARAMETER = "Backend";

    public static final String BACKEND_REFERENCE = "#/components/parameters/" + BACKEND_PARAMETER;

    @Bean
    public OpenApiCustomizer backendParameterCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            StringSchema schema = new StringSchema();
            schema.addEnumItem("auth");
            schema.addEnumItem("open");
            openApi.getComponents().addParameters(
                BACKEND_PARAMETER,
                new PathParameter().name("backend")
                    .description(
                        "`auth` or `open`. `open` answers only where the environment enables open HPDS, and serves no genomic data."
                    ).example("auth").schema(schema)
            );
        };
    }
}
