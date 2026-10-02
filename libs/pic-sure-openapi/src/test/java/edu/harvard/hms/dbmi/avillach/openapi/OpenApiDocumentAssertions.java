package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
 *
 * <p>The schema assertions pin the shapes clients depend on: which component schema a request or response refers to, whether a response is
 * a bare array or the message-and-content envelope, which media type a text body declares, and which properties a schema has. Those that
 * match a {@code $ref} accept it under any media type the operation declares, because springdoc writes a wildcard media type for a handler
 * with no {@code produces}. In every one, {@code method} is lower case and {@code status} is the response code as a string.
 */
public final class OpenApiDocumentAssertions {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final List<String> FRAMEWORK_PACKAGES = List.of("org.springframework.", "org.springdoc.");
    private static final String COMPONENT_PREFIX = "#/components/schemas/";
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number");
    private static final Set<String> BINARY_FORMATS = Set.of("binary", "byte");

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

    /**
     * Asserts that the operation's request body is the named component schema.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param schemaName the key under {@code components.schemas}
     * @throws AssertionError naming the schemas found instead
     */
    public static void assertRequestSchema(JsonNode document, String method, String path, String schemaName) {
        String where = "request body of " + label(method, path);
        requireAny(requestSchemas(document, method, path), schema -> refersTo(schema, schemaName), where, "a $ref to " + schemaName);
    }

    /**
     * Asserts that the operation's request body is a bare array of the named component schema.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param itemSchemaName the key under {@code components.schemas} each item refers to
     * @throws AssertionError naming the schemas found instead
     */
    public static void assertRequestArrayOf(JsonNode document, String method, String path, String itemSchemaName) {
        String where = "request body of " + label(method, path);
        requireAny(
            requestSchemas(document, method, path), schema -> isArray(schema) && refersTo(schema.path("items"), itemSchemaName), where,
            "an array of " + itemSchemaName
        );
    }

    /**
     * Asserts that the response under {@code status} is the named component schema.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param status the response code as the document keys it
     * @param schemaName the key under {@code components.schemas}
     * @throws AssertionError naming the schemas found instead
     */
    public static void assertResponseSchema(JsonNode document, String method, String path, String status, String schemaName) {
        String where = status + " response of " + label(method, path);
        requireAny(
            responseSchemas(document, method, path, status), schema -> refersTo(schema, schemaName), where, "a $ref to " + schemaName
        );
    }

    /**
     * Asserts that the response under {@code status} is a bare array of the named component schema, not an object wrapping one.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param status the response code as the document keys it
     * @param itemSchemaName the key under {@code components.schemas} each item refers to
     * @throws AssertionError naming the schemas found instead
     */
    public static void assertBareArrayOf(JsonNode document, String method, String path, String status, String itemSchemaName) {
        String where = status + " response of " + label(method, path);
        requireAny(
            responseSchemas(document, method, path, status), schema -> isArray(schema) && refersTo(schema.path("items"), itemSchemaName),
            where, "an array of " + itemSchemaName
        );
    }

    /**
     * Asserts that the response under {@code status} is a bare array whose items are a scalar type.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param status the response code as the document keys it
     * @param scalarType the OpenAPI type of each item, such as {@code string} or {@code integer}
     * @throws AssertionError naming the schemas found instead
     */
    public static void assertBareArrayOfScalar(JsonNode document, String method, String path, String status, String scalarType) {
        String where = status + " response of " + label(method, path);
        requireAny(
            responseSchemas(document, method, path, status), schema -> isArray(schema) && types(schema.path("items")).contains(scalarType),
            where, "an array of " + scalarType
        );
    }

    /**
     * Asserts that the response under {@code status} is the message-and-content envelope around an array of the named component schema. The
     * envelope schema is followed through its {@code $ref}, so its generated name does not matter.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param status the response code as the document keys it
     * @param contentItemSchemaName the key under {@code components.schemas} each item of {@code content} refers to
     * @throws AssertionError naming the schemas found instead
     */
    public static void assertEnvelope(JsonNode document, String method, String path, String status, String contentItemSchemaName) {
        String where = status + " response of " + label(method, path);
        requireAny(responseSchemas(document, method, path, status), schema -> {
            JsonNode properties = resolve(document, schema).path("properties");
            JsonNode content = properties.path("content");
            return properties.has("message") && isArray(content) && refersTo(content.path("items"), contentItemSchemaName);
        }, where, "an object with message and a content array of " + contentItemSchemaName);
    }

    /**
     * Asserts that the response under {@code status} declares the media type and that its schema has the given type.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param status the response code as the document keys it
     * @param mediaType the media type key, such as {@code text/html}
     * @param schemaType the OpenAPI type of the body, such as {@code string}
     * @throws AssertionError naming the media types and schema found instead
     */
    public static void assertMediaType(JsonNode document, String method, String path, String status, String mediaType, String schemaType) {
        JsonNode content = response(document, method, path, status).path("content");
        JsonNode schema = content.path(mediaType).path("schema");
        if (!types(schema).contains(schemaType)) {
            throw new AssertionError(
                status + " response of " + label(method, path) + " should declare " + mediaType + " with a schema of type " + schemaType
                    + " but declares " + content
            );
        }
    }

    /**
     * Asserts that the response under {@code status} is declared and carries no body.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param method the HTTP method in lower case
     * @param path the path as the document keys it
     * @param status the response code as the document keys it
     * @throws AssertionError naming the content found
     */
    public static void assertNoResponseBody(JsonNode document, String method, String path, String status) {
        JsonNode content = response(document, method, path, status).path("content");
        if (!content.isMissingNode() && !content.isEmpty()) {
            throw new AssertionError(status + " response of " + label(method, path) + " should have no body but declares " + content);
        }
    }

    /**
     * Asserts that the named component schema has every listed property. Properties declared inside an {@code allOf} member count.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param schemaName the key under {@code components.schemas}
     * @param fields the property names a client reads
     * @throws AssertionError listing the missing properties and the ones present
     */
    public static void assertSchemaHasFields(JsonNode document, String schemaName, String... fields) {
        Map<String, JsonNode> properties = properties(component(document, schemaName));
        List<String> missing = new ArrayList<>();
        for (String field : fields) {
            if (!properties.containsKey(field)) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("schema " + schemaName + " is missing " + missing + "; it has " + properties.keySet());
        }
    }

    /**
     * Asserts that each named component schema meets the {@code @Schema} convention as the document shows it. The schema has a description.
     * Every property has a description, except a property that is only a {@code $ref}, which swagger-core writes with nothing beside it in
     * OpenAPI 3.0 mode. Every string, integer or number property without an {@code enum}, and every array of those, has an example; a
     * nullable property in an OpenAPI 3.1 document lists its type in an array and counts the same way. Every enum, whether the schema, a
     * property or an array's items, lists a description for each of its values.
     *
     * @param document the parsed {@code /v3/api-docs} body
     * @param schemaNames the keys under {@code components.schemas}
     * @throws AssertionError listing every gap in every named schema
     */
    public static void assertSchemaDocumented(JsonNode document, String... schemaNames) {
        List<String> problems = new ArrayList<>();
        for (String schemaName : schemaNames) {
            JsonNode schema = document.path("components").path("schemas").path(schemaName);
            if (schema.isMissingNode()) {
                problems.add(schemaName + " is not in components.schemas");
                continue;
            }
            if (schema.path("description").asText().isBlank()) {
                problems.add(schemaName + " has no description");
            }
            checkEnum(schema, schemaName, problems);
            for (Map.Entry<String, JsonNode> property : properties(schema).entrySet()) {
                checkProperty(schemaName + "." + property.getKey(), property.getValue(), problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("schemas do not meet the @Schema convention:\n  " + String.join("\n  ", problems));
        }
    }

    private static void checkProperty(String label, JsonNode property, List<String> problems) {
        if (property.has("$ref")) {
            return;
        }
        if (property.path("description").asText().isBlank()) {
            problems.add(label + " has no description");
        }
        checkEnum(property, label, problems);
        JsonNode valueSchema = isArray(property) ? property.path("items") : property;
        if (isArray(property)) {
            checkEnum(valueSchema, label + " items", problems);
        }
        boolean scalar = types(valueSchema).stream().anyMatch(SCALAR_TYPES::contains) && !valueSchema.has("enum")
            && !BINARY_FORMATS.contains(valueSchema.path("format").asText());
        if (scalar && !property.has("example")) {
            problems.add(label + " has no example");
        }
    }

    private static void checkEnum(JsonNode schema, String label, List<String> problems) {
        if (!schema.has("enum")) {
            return;
        }
        JsonNode descriptions = schema.path("x-enum-descriptions");
        if (!descriptions.isArray() || descriptions.size() != schema.path("enum").size()) {
            problems.add(label + " lists enum values without x-enum-descriptions");
            return;
        }
        for (int index = 0; index < descriptions.size(); index++) {
            if (descriptions.get(index).asText().isBlank()) {
                problems.add(label + " has no description for enum value " + schema.path("enum").get(index).asText());
            }
        }
    }

    private static Map<String, JsonNode> properties(JsonNode schema) {
        Map<String, JsonNode> properties = new LinkedHashMap<>();
        schema.path("properties").properties().forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
        for (JsonNode member : schema.path("allOf")) {
            member.path("properties").properties().forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
        }
        return properties;
    }

    private static JsonNode component(JsonNode document, String schemaName) {
        JsonNode schema = document.path("components").path("schemas").path(schemaName);
        if (schema.isMissingNode()) {
            throw new AssertionError("schema " + schemaName + " is not in components.schemas");
        }
        return schema;
    }

    private static JsonNode resolve(JsonNode document, JsonNode schema) {
        String ref = schema.path("$ref").asText();
        return ref.startsWith(COMPONENT_PREFIX) ? document.path("components").path("schemas").path(ref.substring(COMPONENT_PREFIX.length()))
            : schema;
    }

    private static boolean refersTo(JsonNode schema, String schemaName) {
        return (COMPONENT_PREFIX + schemaName).equals(schema.path("$ref").asText());
    }

    private static boolean isArray(JsonNode schema) {
        return types(schema).contains("array");
    }

    private static Set<String> types(JsonNode schema) {
        JsonNode type = schema.path("type");
        Set<String> types = new HashSet<>();
        if (type.isTextual()) {
            types.add(type.asText());
        }
        for (JsonNode member : type) {
            if (member.isTextual()) {
                types.add(member.asText());
            }
        }
        return types;
    }

    private static String label(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    private static JsonNode operation(JsonNode document, String method, String path) {
        JsonNode operation = document.path("paths").path(path).path(method);
        if (operation.isMissingNode()) {
            throw new AssertionError("no operation " + label(method, path) + " in the document");
        }
        return operation;
    }

    private static JsonNode response(JsonNode document, String method, String path, String status) {
        JsonNode response = operation(document, method, path).path("responses").path(status);
        if (response.isMissingNode()) {
            throw new AssertionError("no " + status + " response declared on " + label(method, path));
        }
        return response;
    }

    private static List<JsonNode> requestSchemas(JsonNode document, String method, String path) {
        return contentSchemas(operation(document, method, path).path("requestBody").path("content"));
    }

    private static List<JsonNode> responseSchemas(JsonNode document, String method, String path, String status) {
        return contentSchemas(response(document, method, path, status).path("content"));
    }

    private static List<JsonNode> contentSchemas(JsonNode content) {
        List<JsonNode> schemas = new ArrayList<>();
        for (JsonNode mediaType : content) {
            schemas.add(mediaType.path("schema"));
        }
        return schemas;
    }

    private static void requireAny(List<JsonNode> schemas, Predicate<JsonNode> expected, String where, String wanted) {
        if (schemas.stream().noneMatch(expected)) {
            throw new AssertionError(where + " should be " + wanted + " but is " + (schemas.isEmpty() ? "absent" : schemas.toString()));
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
