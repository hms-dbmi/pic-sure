package edu.harvard.hms.dbmi.avillach.openapi;

import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Renders the {@value EnumConstantDescriptionConverter#EXTENSION} extension as a bullet list at the end of each enum schema's description,
 * one line per value: the value in backticks, then its description when it has one. It runs over the finished document instead of inside
 * the converter because swagger-core replaces an enum property's description with the field's own {@code @Schema(description)} after the
 * converter chain returns, which would discard a list written there. {@link OpenApiDocumentWalker} reaches every schema in the document
 * once.
 */
public class EnumDescriptionCustomizer implements GlobalOpenApiCustomizer {

    /**
     * Appends the bullet list to every enum schema in the document that carries the extension.
     *
     * @param openApi the finished document
     */
    @Override
    public void customise(OpenAPI openApi) {
        OpenApiDocumentWalker.forEachSchema(openApi, EnumDescriptionCustomizer::describe);
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
        String list = bullets.substring(1);
        String own = schema.getDescription();
        if (own != null && own.endsWith(list)) {
            return;
        }
        schema.setDescription(own == null || own.isBlank() ? list : own + "\n" + bullets);
    }
}
