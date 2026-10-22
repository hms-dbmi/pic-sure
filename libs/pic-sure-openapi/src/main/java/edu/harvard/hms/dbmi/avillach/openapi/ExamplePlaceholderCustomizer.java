package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.env.PropertyResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
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
 * requires. {@link OpenApiDocumentWalker} reaches each part, and every schema once. An example that is absent stays absent: swagger's
 * setters mark an example as present even when given null, which would publish {@code "example": null}.
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
        OpenApiDocumentWalker.walk(openApi, new OpenApiDocumentWalker.Visitor() {
            @Override
            public void tag(Tag tag) {
                tag.setDescription(resolve(tag.getDescription()));
            }

            @Override
            public void operation(Operation operation) {
                operation.setSummary(resolve(operation.getSummary()));
                operation.setDescription(resolve(operation.getDescription()));
            }

            @Override
            public void parameter(Parameter parameter) {
                parameter.setDescription(resolve(parameter.getDescription()));
                Object example = parameter.getExample();
                if (example != null) {
                    parameter.setExample(resolveValue(example));
                }
                resolveExamples(parameter.getExamples());
            }

            @Override
            public void requestBody(RequestBody requestBody) {
                requestBody.setDescription(resolve(requestBody.getDescription()));
            }

            @Override
            public void response(ApiResponse response) {
                response.setDescription(resolve(response.getDescription()));
            }

            @Override
            public void mediaType(MediaType mediaType) {
                Object example = mediaType.getExample();
                if (example != null) {
                    mediaType.setExample(resolveValue(example));
                }
                resolveExamples(mediaType.getExamples());
            }

            @Override
            public void schema(Schema<?> schema) {
                schema.setDescription(resolve(schema.getDescription()));
                Object example = schema.getExample();
                if (example != null) {
                    schema.setExample(resolveValue(example));
                }
            }
        });
    }

    private void resolveExamples(Map<String, Example> examples) {
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
