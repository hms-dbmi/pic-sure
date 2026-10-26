package edu.harvard.dbmi.avillach.dictionary.concept;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Parameter;
import java.util.Arrays;

/**
 * Gives every operation with a {@link RawConceptPathBody} parameter a request body offered as both {@code text/plain} and
 * {@code application/json}, each described as a raw string with the annotation's example.
 */
@Component
public class RawConceptPathBodyCustomizer implements OperationCustomizer {

    /**
     * @param operation the generated operation
     * @param handlerMethod the controller method behind the operation
     * @return the operation, with its request body replaced when the method takes a {@link RawConceptPathBody}
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Arrays.stream(handlerMethod.getMethod().getParameters()).map(parameter -> parameter.getAnnotation(RawConceptPathBody.class))
            .filter(java.util.Objects::nonNull).findFirst().ifPresent(body -> operation.setRequestBody(rawPathBody(body.example())));
        return operation;
    }

    private static RequestBody rawPathBody(String example) {
        MediaType mediaType =
            new MediaType().schema(new StringSchema().description(ConceptController.RAW_CONCEPT_PATH_BODY).example(example));
        return new RequestBody().required(true)
            .content(new Content().addMediaType("text/plain", mediaType).addMediaType("application/json", mediaType));
    }
}
