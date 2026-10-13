package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

import java.io.IOException;

/**
 * Writes each concept in {@link ConceptPage#content()} exactly as Spring Data's {@code PageImpl} wrote it, so the page's JSON is unchanged.
 *
 * <p> A concept usually carries {@code type} twice. {@link Concept}'s {@code @JsonTypeInfo} writes it first as the type id, and the
 * record's {@code type()} accessor writes it again as the last property. {@code PageImpl} holds its content in a generic list, so Jackson
 * did not apply {@code @JsonTypeInfo} there and each concept carried {@code type} once, at the end. {@link ConceptPage} declares its
 * content as {@code List<Concept>}, which would bring the leading type id back. This serializer leaves it out.
 *
 * <p> Only the concepts directly in {@code content} are affected. A concept nested under {@code children} or {@code table} is written as
 * usual and still carries {@code type} twice.
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
