package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

import java.io.IOException;

/**
 * Writes a concept that sits directly in a page's {@code content} without Jackson's leading type id, which is how the concept endpoints
 * have always written it. Spring Data's {@code PageImpl} exposes its content as an untyped list, so Jackson never applied {@link Concept}'s
 * {@code @JsonTypeInfo} to those elements and each one carries {@code type} once, as its last property. A {@code List<Concept>} component
 * would add the type id in front, so {@link ConceptPage} names this serializer for its content instead. Concepts nested under
 * {@code children} or {@code table} are untouched and keep both.
 */
public class PageContentConceptSerializer extends JsonSerializer<Concept> {

    /**
     * Writes the concept with the serializer of its own record class and no type id.
     *
     * @param value the concept to write
     * @param gen the generator writing the page
     * @param serializers the provider that holds the record serializers
     * @throws IOException when the generator cannot write
     */
    @Override
    public void serialize(Concept value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        serializers.findValueSerializer(value.getClass()).serialize(value, gen, serializers);
    }

    /**
     * Ignores the type serializer Jackson derives from {@link Concept}'s {@code @JsonTypeInfo} and writes the concept as
     * {@link #serialize(Concept, JsonGenerator, SerializerProvider)} does.
     *
     * @param value the concept to write
     * @param gen the generator writing the page
     * @param serializers the provider that holds the record serializers
     * @param typeSer the type serializer that would have written the leading type id
     * @throws IOException when the generator cannot write
     */
    @Override
    public void serializeWithType(Concept value, JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer)
        throws IOException {
        serialize(value, gen, serializers);
    }
}
