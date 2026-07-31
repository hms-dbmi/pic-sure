package edu.harvard.hms.dbmi.avillach.commons.openapi;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes a service's springdoc document to {@code docs/api/<artifactId>.openapi.json} at the repo root, and asserts the invariants that
 * hold across every service's document.
 *
 * <p>Shared rather than copied six times because both halves must not drift: a per-service copy of the canonicalizer would silently produce
 * documents that diff against each other for formatting reasons, and a per-service copy of the invariants would let one service quietly
 * stop checking that the {@code QueryRequest} envelope is gone.
 *
 * <p>Deliberately framework-free (plain {@link AssertionError}, no JUnit/AssertJ): it ships in this module's test-jar and is consumed by
 * modules whose test-scope assertion libraries are not this module's business.
 */
public final class OpenApiExport {

    /** The reactor root's artifactId. Located by walking up, never hardcoded, so this works in any worktree or CI checkout. */
    private static final String ROOT_ARTIFACT_ID = "pic-sure-api";

    /** The envelope types the v3 contract redesign removed. Neither may reappear as a schema in any document. */
    private static final Set<String> BANNED_SCHEMA_NAMES = Set.of("QueryRequest", "GeneralQueryRequest");

    /** The only status values the v3 wire carries, in the order {@code PicSureStatus} declares them. */
    private static final List<String> QUERY_STATUS_VALUES = List.of("QUEUED", "PENDING", "ERROR", "AVAILABLE");

    private static final ObjectMapper CANONICAL = new ObjectMapper()
        // Sorted keys at every level: springdoc's own map ordering is not stable across JDK/classpath changes, and an
        // unstable order would make every regeneration a spurious diff.
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    private OpenApiExport() {}

    /**
     * Canonicalizes {@code rawJson} and writes it to {@code docs/api/<artifactId>.openapi.json}.
     *
     * @return the parsed document, for per-service assertions
     */
    public static JsonNode write(String artifactId, String rawJson) {
        JsonNode parsed = parse(rawJson);
        Path target = repoRoot().resolve("docs").resolve("api").resolve(artifactId + ".openapi.json");
        try {
            Files.createDirectories(target.getParent());
            // Explicit LF + trailing newline: the platform default would make the file's bytes depend on which OS
            // regenerated it.
            Files.writeString(target, canonicalize(rawJson) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + target, e);
        }
        return parsed;
    }

    /** Pretty-prints with sorted keys, 2-space indent and LF newlines, so regeneration is diff-stable. */
    public static String canonicalize(String rawJson) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        ObjectWriter writer = CANONICAL.writer(printer);
        try {
            // Through TreeMap rather than JsonNode: ObjectNode preserves insertion order and ignores
            // ORDER_MAP_ENTRIES_BY_KEYS, so only a real Map round trip actually sorts the keys.
            return writer.writeValueAsString(CANONICAL.readValue(rawJson, TreeMap.class));
        } catch (IOException e) {
            throw new UncheckedIOException("document is not valid JSON", e);
        }
    }

    public static JsonNode parse(String rawJson) {
        try {
            return CANONICAL.readTree(rawJson);
        } catch (IOException e) {
            throw new UncheckedIOException("document is not valid JSON", e);
        }
    }

    /**
     * The invariants every service's document must satisfy: the {@code QueryRequest}/{@code GeneralQueryRequest} envelopes are gone, and
     * wherever {@code QueryStatusResponse} appears its {@code status} enumerates exactly the four real v3 statuses.
     */
    public static void assertSharedContractInvariants(JsonNode document) {
        assertNoEnvelopeSchemas(document);
        assertQueryStatusEnum(document);
    }

    private static void assertNoEnvelopeSchemas(JsonNode document) {
        JsonNode schemas = document.path("components").path("schemas");
        schemas.fieldNames().forEachRemaining(name -> {
            if (BANNED_SCHEMA_NAMES.contains(name)) {
                throw new AssertionError("the removed " + name + " envelope is back in the document as a schema");
            }
        });
        // A banned name can also reappear only as a $ref target (a schema referenced but not defined); catch that too.
        String rendered = document.toString();
        for (String banned : BANNED_SCHEMA_NAMES) {
            if (rendered.contains("#/components/schemas/" + banned + "\"")) {
                throw new AssertionError("the removed " + banned + " envelope is referenced by the document");
            }
        }
    }

    /** No-op when the service does not serve {@code QueryStatusResponse}; that is a per-service fact, not a violation. */
    private static void assertQueryStatusEnum(JsonNode document) {
        JsonNode schema = document.path("components").path("schemas").path("QueryStatusResponse");
        if (schema.isMissingNode()) {
            return;
        }
        JsonNode status = schema.path("properties").path("status");
        // springdoc renders an enum-valued property either inline or as a $ref to the enum's own schema.
        JsonNode enumNode =
            status.has("enum") ? status.path("enum") : document.path("components").path("schemas").path(refName(status)).path("enum");
        if (!enumNode.isArray()) {
            throw new AssertionError("QueryStatusResponse.status carries no enum; found: " + status);
        }
        List<String> actual = new java.util.ArrayList<>();
        enumNode.forEach(value -> actual.add(value.asText()));
        if (!QUERY_STATUS_VALUES.equals(actual)) {
            throw new AssertionError("QueryStatusResponse.status enum is " + actual + ", expected " + QUERY_STATUS_VALUES);
        }
    }

    private static String refName(JsonNode status) {
        String ref = status.has("$ref") ? status.path("$ref").asText()
            : status.path("allOf").path(0).path("$ref").asText(status.path("oneOf").path(0).path("$ref").asText(""));
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    /** Asserts the document declares an operation at {@code path} with the given HTTP method. */
    public static JsonNode operation(JsonNode document, String path, String method) {
        JsonNode operation = document.path("paths").path(path).path(method.toLowerCase(java.util.Locale.ROOT));
        if (operation.isMissingNode()) {
            throw new AssertionError(
                method + " " + path + " is missing from the document; paths present: " + fieldNames(document.path("paths"))
            );
        }
        return operation;
    }

    /**
     * Asserts the 200 response of {@code operation} on {@code mediaType} is the named schema (bare, or a list of it). A handler that
     * declares no {@code produces} is documented under {@code * / *}, which is accepted as the same content.
     */
    public static void assertRespondsWith(JsonNode operation, String mediaType, String schemaName) {
        JsonNode content = operation.path("responses").path("200").path("content");
        JsonNode schema = content.has(mediaType) ? content.path(mediaType).path("schema") : content.path("*/*").path("schema");
        if (schema.isMissingNode()) {
            throw new AssertionError("no 200/" + mediaType + " response schema; operation was " + operation);
        }
        JsonNode subject = "array".equals(schema.path("type").asText()) ? schema.path("items") : schema;
        String actual = refName(subject);
        if (!schemaName.equals(actual)) {
            throw new AssertionError(
                "200/" + mediaType + " response is " + (actual.isEmpty() ? subject : actual) + ", expected " + schemaName
            );
        }
    }

    /** Asserts the request body of {@code operation} on {@code mediaType} is the named schema. */
    public static void assertAcceptsSchema(JsonNode operation, String mediaType, String schemaName) {
        JsonNode content = operation.path("requestBody").path("content");
        JsonNode schema = content.has(mediaType) ? content.path(mediaType).path("schema") : content.path("*/*").path("schema");
        if (schema.isMissingNode()) {
            throw new AssertionError("no " + mediaType + " request body schema; operation was " + operation);
        }
        String actual = refName(schema);
        if (!schemaName.equals(actual)) {
            throw new AssertionError("request body is " + (actual.isEmpty() ? schema : actual) + ", expected " + schemaName);
        }
    }

    /** Asserts a named schema's property is (a list of, or a reference to) another named schema. */
    public static void assertPropertyIsSchema(JsonNode document, String schemaName, String property, String expected) {
        JsonNode node = document.path("components").path("schemas").path(schemaName).path("properties").path(property);
        if (node.isMissingNode()) {
            throw new AssertionError(schemaName + " has no property " + property);
        }
        JsonNode subject = "array".equals(node.path("type").asText()) ? node.path("items") : node;
        String actual = refName(subject);
        if (!expected.equals(actual)) {
            throw new AssertionError(
                schemaName + "." + property + " is " + (actual.isEmpty() ? subject : actual) + ", expected " + expected
            );
        }
    }

    /** Asserts a named schema declares exactly these property names -- the check for a shape that must not gain or lose fields. */
    public static void assertSchemaProperties(JsonNode document, String schemaName, List<String> expected) {
        JsonNode schema = document.path("components").path("schemas").path(schemaName);
        if (schema.isMissingNode()) {
            throw new AssertionError("the document declares no schema named " + schemaName);
        }
        List<String> actual = fieldNames(schema.path("properties"));
        List<String> wanted = new java.util.ArrayList<>(expected);
        java.util.Collections.sort(wanted);
        if (!wanted.equals(actual)) {
            throw new AssertionError(schemaName + " properties are " + actual + ", expected " + wanted);
        }
    }

    /** Asserts the named parameter of {@code operation} carries a description containing {@code fragment}. */
    public static void assertParameterDescribes(JsonNode operation, String parameterName, String fragment) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (parameterName.equals(parameter.path("name").asText())) {
                String description = parameter.path("description").asText("");
                if (!description.contains(fragment)) {
                    throw new AssertionError(
                        "parameter " + parameterName + " description does not mention '" + fragment + "'; it reads: " + description
                    );
                }
                return;
            }
        }
        throw new AssertionError("operation declares no parameter named " + parameterName + "; operation was " + operation);
    }

    /** Asserts the operation's own summary/description mentions {@code fragment} -- how an untyped response documents its variance. */
    public static void assertDescriptionMentions(JsonNode operation, String fragment) {
        String prose = operation.path("summary").asText("") + " " + operation.path("description").asText("");
        if (!prose.contains(fragment)) {
            throw new AssertionError("operation description does not mention '" + fragment + "'; it reads: " + prose.trim());
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);
        return names;
    }

    /**
     * Walks up from the working directory to the reactor root -- the directory whose {@code pom.xml} declares artifactId
     * {@code pic-sure-api}. Surefire runs each module with its own module directory as the working directory, so the root is 1-4 levels up
     * depending on the module; hardcoding either a depth or an absolute path would break the moment a module moves or the tree is checked
     * out somewhere else.
     */
    public static Path repoRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom) && declaresRootArtifact(pom)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
            "no pom.xml declaring <artifactId>" + ROOT_ARTIFACT_ID + "</artifactId> above " + Paths.get("").toAbsolutePath()
        );
    }

    private static boolean declaresRootArtifact(Path pom) {
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            // Only the root declares it as its OWN artifactId; every module below names it inside a <parent> block,
            // so a match preceded by an unclosed <parent> is a module, not the root.
            int marker = text.indexOf("<artifactId>" + ROOT_ARTIFACT_ID + "</artifactId>");
            if (marker < 0) {
                return false;
            }
            int parentOpen = text.lastIndexOf("<parent>", marker);
            return parentOpen < 0 || parentOpen < text.lastIndexOf("</parent>", marker);
        } catch (IOException e) {
            return false;
        }
    }
}
