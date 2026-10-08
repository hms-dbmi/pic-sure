package edu.harvard.hms.dbmi.avillach.openapi;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.springdoc.core.providers.ObjectMapperProvider;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Carries the {@code @Schema(description)} of each enum constant into the OpenAPI document, which swagger-core reads for the enum type and
 * drops for its constants. For every enum schema the converter chain produces, inline or as a component, this converter adds the
 * {@value #EXTENSION} extension: one string per entry of the schema's {@code enum} array, in the same order, empty for a constant with no
 * description. {@link EnumDescriptionCustomizer} later renders that extension as a bullet list in the schema's description. A constant is
 * matched to its wire value by serializing it with springdoc's own mapper, so {@code @JsonProperty} and {@code @JsonValue} renames line up
 * with what the document lists.
 */
public class EnumConstantDescriptionConverter implements ModelConverter {

    /** The vendor extension holding one description per enum value. */
    public static final String EXTENSION = "x-enum-descriptions";

    private static final String COMPONENT_PREFIX = "#/components/schemas/";

    private final ObjectMapperProvider objectMapperProvider;

    /**
     * Creates the converter.
     *
     * @param objectMapperProvider springdoc's mapper source, the same mapper swagger-core derives enum values with
     */
    public EnumConstantDescriptionConverter(ObjectMapperProvider objectMapperProvider) {
        this.objectMapperProvider = objectMapperProvider;
    }

    /**
     * Resolves the type through the rest of the chain, then annotates the result when the type is an enum.
     *
     * @param type the type being resolved
     * @param context the resolution context, which holds component schemas already defined
     * @param chain the remaining converters
     * @return the schema the chain produced, with the extension added when it describes an enum
     */
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (schema == null) {
            return null;
        }
        ObjectMapper mapper = objectMapperProvider.jsonMapper();
        JavaType javaType = mapper.constructType(type.getType());
        Class<?> raw = javaType.getRawClass();
        if (!raw.isEnum()) {
            return schema;
        }
        Schema<?> target = schema;
        if (schema.get$ref() != null && schema.get$ref().startsWith(COMPONENT_PREFIX)) {
            target = context.getDefinedModels().get(schema.get$ref().substring(COMPONENT_PREFIX.length()));
        }
        if (target == null || target.getEnum() == null) {
            return schema;
        }
        if (target.getExtensions() != null && target.getExtensions().containsKey(EXTENSION)) {
            return schema;
        }
        List<String> descriptions = new ArrayList<>();
        for (Object value : target.getEnum()) {
            descriptions.add(describe(raw, String.valueOf(value), mapper));
        }
        target.addExtension(EXTENSION, descriptions);
        return schema;
    }

    private static String describe(Class<?> enumType, String wireValue, ObjectMapper mapper) {
        for (Field field : enumType.getDeclaredFields()) {
            if (!field.isEnumConstant()) {
                continue;
            }
            Object constant = Enum.valueOf(enumType.asSubclass(Enum.class), field.getName());
            if (wireValue.equals(mapper.valueToTree(constant).asText())) {
                io.swagger.v3.oas.annotations.media.Schema annotation =
                    field.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
                return annotation == null ? "" : annotation.description();
            }
        }
        return "";
    }
}
