package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Renders the {@value EnumConstantDescriptionConverter#EXTENSION} extension as a bullet list at the end of each enum schema's description,
 * one line per value: the value in backticks, then its description when it has one. It runs over the finished document instead of inside
 * the converter because swagger-core replaces an enum property's description with the field's own {@code @Schema(description)} after the
 * converter chain returns, which would discard a list written there. Every schema in the document is visited once: components, request
 * bodies, responses and parameters, through properties, items, additional properties and composed schemas.
 */
public class EnumDescriptionCustomizer implements GlobalOpenApiCustomizer {

    /**
     * Appends the bullet list to every enum schema in the document that carries the extension.
     *
     * @param openApi the finished document
     */
    @Override
    public void customise(OpenAPI openApi) {
        Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(schema -> visit(schema, seen));
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
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                visit(parameter.getSchema(), seen);
                visitContent(parameter.getContent(), seen);
            }
        }
        if (operation.getRequestBody() != null) {
            visitContent(operation.getRequestBody().getContent(), seen);
        }
        if (operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
                visitContent(response.getContent(), seen);
            }
        }
    }

    private void visitContent(Content content, Set<Schema<?>> seen) {
        if (content == null) {
            return;
        }
        for (MediaType mediaType : content.values()) {
            visit(mediaType.getSchema(), seen);
        }
    }

    private void visit(Schema<?> schema, Set<Schema<?>> seen) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        describe(schema);
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> visit(property, seen));
        }
        visit(schema.getItems(), seen);
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            visit(additional, seen);
        }
        visitAll(schema.getAllOf(), seen);
        visitAll(schema.getOneOf(), seen);
        visitAll(schema.getAnyOf(), seen);
    }

    private void visitAll(List<Schema> schemas, Set<Schema<?>> seen) {
        if (schemas != null) {
            schemas.forEach(schema -> visit(schema, seen));
        }
    }

    private static void describe(Schema<?> schema) {
        Map<String, Object> extensions = schema.getExtensions();
        if (
            schema.getEnum() == null || extensions == null
                || !(extensions.get(EnumConstantDescriptionConverter.EXTENSION) instanceof List<?> descriptions)
                || descriptions.size() != schema.getEnum().size()
        ) {
            return;
        }
        StringBuilder bullets = new StringBuilder();
        for (int index = 0; index < descriptions.size(); index++) {
            String text = String.valueOf(descriptions.get(index));
            bullets.append("\n- `").append(schema.getEnum().get(index)).append('`');
            if (!text.isBlank()) {
                bullets.append(": ").append(text);
            }
        }
        String own = schema.getDescription();
        if (own != null && own.endsWith(bullets.toString())) {
            return;
        }
        schema.setDescription(own == null || own.isBlank() ? bullets.substring(1) : own + "\n" + bullets);
    }
}
