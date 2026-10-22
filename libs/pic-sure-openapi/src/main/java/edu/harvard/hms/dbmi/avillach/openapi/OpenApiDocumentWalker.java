package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Walks a finished OpenAPI document for the customizers that rewrite it: its tags, every component schema, and each operation with its
 * parameters, request body, responses and the media types of their content. Schemas are reached through components, parameters and content,
 * and walked through properties, items, additional properties and composed schemas. Each schema is visited once, however many places refer
 * to it. A part is handed to the visitor before the walk goes into what it contains.
 */
final class OpenApiDocumentWalker {

    /** What the walk does with each part it reaches. Every callback does nothing unless overridden. */
    interface Visitor {

        default void tag(Tag tag) {}

        default void operation(Operation operation) {}

        default void parameter(Parameter parameter) {}

        default void requestBody(RequestBody requestBody) {}

        default void response(ApiResponse response) {}

        default void mediaType(MediaType mediaType) {}

        default void schema(Schema<?> schema) {}
    }

    private final Visitor visitor;

    private final Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    private OpenApiDocumentWalker(Visitor visitor) {
        this.visitor = visitor;
    }

    /**
     * Hands every tag, schema, operation, parameter, request body, response and media type of the document to the visitor.
     *
     * @param openApi the finished document
     * @param visitor what to do with each part
     */
    static void walk(OpenAPI openApi, Visitor visitor) {
        new OpenApiDocumentWalker(visitor).walkDocument(openApi);
    }

    /**
     * Hands every schema of the document, each once, to {@code action}.
     *
     * @param openApi the finished document
     * @param action what to do with each schema
     */
    static void forEachSchema(OpenAPI openApi, Consumer<Schema<?>> action) {
        walk(openApi, new Visitor() {
            @Override
            public void schema(Schema<?> schema) {
                action.accept(schema);
            }
        });
    }

    private void walkDocument(OpenAPI openApi) {
        if (openApi.getTags() != null) {
            openApi.getTags().forEach(visitor::tag);
        }
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(this::walkSchema);
        }
        if (openApi.getPaths() == null) {
            return;
        }
        for (PathItem item : openApi.getPaths().values()) {
            for (Operation operation : item.readOperations()) {
                walkOperation(operation);
            }
        }
    }

    private void walkOperation(Operation operation) {
        visitor.operation(operation);
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                visitor.parameter(parameter);
                walkSchema(parameter.getSchema());
                walkContent(parameter.getContent());
            }
        }
        if (operation.getRequestBody() != null) {
            visitor.requestBody(operation.getRequestBody());
            walkContent(operation.getRequestBody().getContent());
        }
        if (operation.getResponses() != null) {
            for (ApiResponse response : operation.getResponses().values()) {
                visitor.response(response);
                walkContent(response.getContent());
            }
        }
    }

    private void walkContent(Content content) {
        if (content == null) {
            return;
        }
        for (MediaType mediaType : content.values()) {
            visitor.mediaType(mediaType);
            walkSchema(mediaType.getSchema());
        }
    }

    private void walkSchema(Schema<?> schema) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        visitor.schema(schema);
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(this::walkSchema);
        }
        walkSchema(schema.getItems());
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            walkSchema(additional);
        }
        walkAll(schema.getAllOf());
        walkAll(schema.getOneOf());
        walkAll(schema.getAnyOf());
    }

    @SuppressWarnings("rawtypes")
    private void walkAll(List<Schema> schemas) {
        if (schemas != null) {
            schemas.forEach(this::walkSchema);
        }
    }
}
