package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;

/**
 * Splits this service's OpenAPI document by HPDS backend. The {@value #AUTH_GROUP} group documents the authorized queries and searches
 * under their exact {@code /hpds/auth/...} paths. The {@value #OPEN_GROUP} group documents open access, which the aggregate controller
 * serves at {@code /hpds/open/...}, and exists only when an open backend is configured ({@code HPDS_OPEN_URL}), so a deployment without
 * open access publishes no open documentation. Routing is unchanged: the generic controllers still answer {@code /hpds/{backend}/...}, and
 * only the documents pin the segment.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", matchIfMissing = true)
public class OpenApiGroupsConfig {

    static final String AUTH_GROUP = "auth";

    static final String OPEN_GROUP = "open";

    static final String OPEN_TITLE = "PIC-SURE High Performance Data Store (HPDS) Open Access";

    private static final String BACKEND_SEGMENT = "{backend}";

    /**
     * The authorized document: every {@code /hpds/{backend}/...} operation with the segment pinned to {@code auth}, and none of the open
     * aggregate paths.
     *
     * @param title the service's document title, {@code picsure.openapi.title}
     * @return the group springdoc serves at {@code /v3/api-docs/auth}
     */
    @Bean
    public GroupedOpenApi authorizedQueriesApi(@Value("${picsure.openapi.title:}") String title) {
        return GroupedOpenApi.builder().group(AUTH_GROUP).displayName(title.isBlank() ? AUTH_GROUP : title).pathsToMatch("/hpds/**")
            .pathsToExclude("/hpds/open/**").addOpenApiCustomizer(openApi -> pinBackend(retitle(openApi, title), AUTH_GROUP)).build();
    }

    /**
     * The open access document: the aggregate controller's {@code /hpds/open/...} operations. Registered only when {@code hpds.open-url}
     * names an open backend.
     *
     * @return the group springdoc serves at {@code /v3/api-docs/open}
     */
    @Bean
    @ConditionalOnExpression("!'${hpds.open-url:}'.isBlank()")
    public GroupedOpenApi openAccessQueriesApi() {
        return GroupedOpenApi.builder().group(OPEN_GROUP).displayName(OPEN_TITLE).pathsToMatch("/hpds/open/**")
            .addOpenApiCustomizer(openApi -> retitle(openApi, OPEN_TITLE)).build();
    }

    private static OpenAPI retitle(OpenAPI openApi, String title) {
        if (title != null && !title.isBlank()) {
            Info info = openApi.getInfo() == null ? new Info() : openApi.getInfo();
            openApi.setInfo(new Info().title(title).version(info.getVersion()).description(info.getDescription()));
        }
        return openApi;
    }

    /**
     * Replaces {@code {backend}} in every path with {@code backend} and drops the {@code backend} path parameter from each operation, so
     * the document shows the exact path a caller sends.
     */
    static void pinBackend(OpenAPI openApi, String backend) {
        if (openApi.getPaths() == null) {
            return;
        }
        Map<String, PathItem> pinned = new LinkedHashMap<>();
        openApi.getPaths().forEach((path, item) -> {
            if (path.contains(BACKEND_SEGMENT)) {
                if (item.getParameters() != null) {
                    item.getParameters().removeIf(parameter -> "backend".equals(parameter.getName()));
                }
                for (Operation operation : item.readOperations()) {
                    if (operation.getParameters() != null) {
                        operation.getParameters().removeIf(parameter -> "backend".equals(parameter.getName()));
                    }
                }
            }
            pinned.put(path.replace(BACKEND_SEGMENT, backend), item);
        });
        Paths paths = new Paths();
        paths.putAll(pinned);
        openApi.setPaths(paths);
    }
}
