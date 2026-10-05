package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Checks that a served OpenAPI document covers a service's handler map: every handler in {@link RequestMappingHandlerMapping} that is not
 * hidden and not the framework's own appears under its path and HTTP method, and every documented operation carries a non-blank summary.
 * Framework-free on purpose (plain {@link AssertionError}), so a consuming module keeps its own assertion library. A mapping that declares
 * no HTTP method is satisfied by the path having any operation at all, since springdoc expands it to every method it knows and that set is
 * springdoc's to choose.
 */
public final class OpenApiDocumentAssertions {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final List<String> FRAMEWORK_PACKAGES = List.of("org.springframework.", "org.springdoc.");

    private OpenApiDocumentAssertions() {}

    /**
     * Asserts that {@code document} covers every visible handler registered in {@code handlerMapping}.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param handlerMapping the service's {@code requestMappingHandlerMapping} bean
     * @throws AssertionError listing every uncovered handler and every blank summary
     */
    public static void assertCovers(JsonNode document, RequestMappingHandlerMapping handlerMapping) {
        assertCovers(document, handlerMapping.getHandlerMethods());
    }

    /**
     * Asserts that {@code document} covers every visible handler in {@code handlerMethods}.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param handlerMethods mapping info to handler, as {@link RequestMappingHandlerMapping#getHandlerMethods()} returns it
     * @throws AssertionError listing every uncovered handler and every blank summary
     */
    public static void assertCovers(JsonNode document, Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        JsonNode paths = document.path("paths");
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (isFrameworkHandler(handler) || isHidden(handler)) {
                continue;
            }
            String label = handler.getShortLogMessage();
            for (String pattern : entry.getKey().getPatternValues()) {
                JsonNode item = paths.get(pattern);
                if (item == null || !item.isObject()) {
                    problems.add("missing path " + pattern + " (" + label + ")");
                    continue;
                }
                Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    if (item.isEmpty()) {
                        problems.add("no operations under " + pattern + " (" + label + ")");
                    }
                    continue;
                }
                for (RequestMethod method : methods) {
                    if (!item.has(method.name().toLowerCase(Locale.ROOT))) {
                        problems.add("missing operation " + method.name() + " " + pattern + " (" + label + ")");
                    }
                }
            }
        }
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (HTTP_METHODS.contains(operation.getKey()) && operation.getValue().path("summary").asText().isBlank()) {
                    problems.add("blank summary on " + operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("OpenAPI document does not cover the handler map:\n  " + String.join("\n  ", problems));
        }
    }

    private static boolean isFrameworkHandler(HandlerMethod handler) {
        String packageName = handler.getBeanType().getPackageName();
        return FRAMEWORK_PACKAGES.stream().anyMatch(packageName::startsWith);
    }

    private static boolean isHidden(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Hidden.class) || handler.hasMethodAnnotation(Hidden.class);
    }
}
