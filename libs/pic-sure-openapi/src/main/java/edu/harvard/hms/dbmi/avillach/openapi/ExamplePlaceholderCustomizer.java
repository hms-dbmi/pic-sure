package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.env.PropertyResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Resolves Spring {@code ${key:default}} placeholders in the finished document, so a deployment can swap the concept paths, datasets and
 * other values its examples name for ones that exist in its own data. A service writes each such value as a placeholder whose default is
 * the value that works on the all-in-one, and a deployment overrides the key like any other property, for example through an environment
 * variable. A placeholder with no default and no value is left as it is.
 *
 * <p>Tag, operation, parameter, request body and response descriptions and summaries are resolved, as are examples on parameters, media
 * types and schemas. An example parsed from JSON is rebuilt with each text value resolved, so a substituted value is escaped as JSON
 * requires. Schemas are walked through properties, items, additional properties and composed schemas, each once. An example that is absent
 * stays absent: swagger's setters mark an example as present even when given null, which would publish {@code "example": null}.
 */
public class ExamplePlaceholderCustomizer implements GlobalOpenApiCustomizer {

    private static final String PLACEHOLDER_START = "${";

    private final PropertyResolver properties;

    /**
     * @param properties the service's environment, which supplies each placeholder's value
     */
    public ExamplePlaceholderCustomizer(PropertyResolver properties) {
        this.properties = properties;
    }

    /**
     * Resolves every placeholder the document's descriptions, summaries and examples carry.
     *
     * @param openApi the finished document
     */
    @Override
    public void customise(OpenAPI openApi) {
        Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (openApi.getTags() != null) {
            for (Tag tag : openApi.getTags()) {
                tag.setDescription(resolve(tag.getDescription()));
            }
        }
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(schema -> visitSchema(schema, seen));
        }
        if (openApi.getPaths() == null) {
            return;
        }
        for (PathItem item : openApi.getPaths().values()) {
            for (Operation operation : item.readOperations()) {
                visitOperation(operation, seen);
            }
        }
    }

    private void visitOperation(Operation operation, Set<Schema<?>> seen) {
        operation.setSummary(resolve(operation.getSummary()));
        operation.setDescription(resolve(operation.getDescription()));
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                parameter.setDescription(resolve(parameter.getDescription()));
                Object parameterExample = parameter.getExample();
                if (parameterExample != null) {
                    parameter.setExample(resolveValue(parameterExample));
                }
                visitExamples(parameter.getExamples());
                visitSchema(parameter.getSchema(), seen);
                visitContent(parameter.getContent(), seen);
            }
        }
        if (operation.getRequestBody() != null) {
            operation.getRequestBody().setDescription(resolve(operation.getRequestBody().getDescription()));
            visitContent(operation.getRequestBody().getContent(), seen);
        }
        if (operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
                response.setDescription(resolve(response.getDescription()));
                visitContent(response.getContent(), seen);
            }
        }
    }

    private void visitContent(Content content, Set<Schema<?>> seen) {
        if (content == null) {
            return;
        }
        for (MediaType mediaType : content.values()) {
            Object mediaTypeExample = mediaType.getExample();
            if (mediaTypeExample != null) {
                mediaType.setExample(resolveValue(mediaTypeExample));
            }
            visitExamples(mediaType.getExamples());
            visitSchema(mediaType.getSchema(), seen);
        }
    }

    private void visitExamples(Map<String, Example> examples) {
        if (examples == null) {
            return;
        }
        for (Example example : examples.values()) {
            example.setSummary(resolve(example.getSummary()));
            example.setDescription(resolve(example.getDescription()));
            Object value = example.getValue();
            if (value != null) {
                example.setValue(resolveValue(value));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void visitSchema(Schema schema, Set<Schema<?>> seen) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        schema.setDescription(resolve(schema.getDescription()));
        Object schemaExample = schema.getExample();
        if (schemaExample != null) {
            schema.setExample(resolveValue(schemaExample));
        }
        if (schema.getProperties() != null) {
            ((Map<String, Schema>) schema.getProperties()).values().forEach(property -> visitSchema(property, seen));
        }
        visitSchema(schema.getItems(), seen);
        if (schema.getAdditionalProperties() instanceof Schema additional) {
            visitSchema(additional, seen);
        }
        for (List<Schema> composed : new List[] {schema.getAllOf(), schema.getAnyOf(), schema.getOneOf()}) {
            if (composed != null) {
                composed.forEach(part -> visitSchema(part, seen));
            }
        }
    }

    private String resolve(String text) {
        return text == null || !text.contains(PLACEHOLDER_START) ? text : properties.resolvePlaceholders(text);
    }

    private Object resolveValue(Object value) {
        if (value instanceof String text) {
            return resolve(text);
        }
        if (value instanceof JsonNode node) {
            return resolveNode(node);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, entry) -> resolved.put(key, resolveValue(entry)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            list.forEach(entry -> resolved.add(resolveValue(entry)));
            return resolved;
        }
        return value;
    }

    private JsonNode resolveNode(JsonNode node) {
        if (node.isTextual()) {
            return TextNode.valueOf(resolve(node.textValue()));
        }
        if (node.isObject()) {
            ObjectNode resolved = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(field -> resolved.set(field.getKey(), resolveNode(field.getValue())));
            return resolved;
        }
        if (node.isArray()) {
            ArrayNode resolved = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> resolved.add(resolveNode(element)));
            return resolved;
        }
        return node;
    }
}
