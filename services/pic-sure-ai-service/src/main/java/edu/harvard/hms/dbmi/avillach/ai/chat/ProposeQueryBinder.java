package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Binds {@code propose_query}'s arguments strictly, mirroring {@code pic-sure-mcp}'s own {@code query.QueryBinder}: an unknown field fails
 * instead of being silently dropped (which would hide a disallowed field, such as {@code not}, rather than rejecting it), and every binding
 * error becomes a one-sentence, model-facing message instead of a raw Jackson exception.
 */
final class ProposeQueryBinder {

    /** Longest field name echoed back in a failure message. */
    static final int MAX_FIELD_NAME_LENGTH = 64;

    private static final ObjectMapper STRICT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    private ProposeQueryBinder() {}

    /**
     * Binds a {@code propose_query} call's arguments to its input record.
     *
     * @param arguments the call's arguments, possibly null
     * @param type the input record
     * @param <T> the input type
     * @return the bound input
     * @throws IllegalArgumentException naming the offending field when an argument is unknown, of the wrong type, or not a valid clause
     */
    static <T> T bind(Map<String, Object> arguments, Class<T> type) {
        try {
            return STRICT.convertValue(arguments == null ? Map.of() : arguments, type);
        } catch (IllegalArgumentException e) {
            throw e.getCause() instanceof JsonMappingException mapping ? describe(mapping) : unreadable();
        }
    }

    private static IllegalArgumentException describe(JsonMappingException e) {
        if (e instanceof UnrecognizedPropertyException unknown) {
            return new IllegalArgumentException("Field '" + safe(unknown.getPropertyName()) + "' is not part of this tool's input.");
        }
        if (
            e instanceof InvalidTypeIdException typeId && typeId.getBaseType() != null
                && typeId.getBaseType().hasRawClass(ProposeQueryInput.Clause.class)
        ) {
            return new IllegalArgumentException(
                "Each phenotypic clause must be a filter (phenotypicFilterType and conceptPath) or a subquery (operator and "
                    + "phenotypicClauses)."
            );
        }
        String field = lastField(e.getPath());
        if (e instanceof InvalidFormatException format && format.getTargetType() != null && format.getTargetType().isEnum()) {
            String allowed =
                Arrays.stream(format.getTargetType().getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
            return new IllegalArgumentException("Field '" + field + "' must be one of " + allowed + ".");
        }
        if (e instanceof MismatchedInputException) {
            return new IllegalArgumentException("Field '" + field + "' has the wrong type.");
        }
        return unreadable();
    }

    private static IllegalArgumentException unreadable() {
        return new IllegalArgumentException("The arguments could not be read. Check them against the tool's input schema.");
    }

    private static String lastField(List<JsonMappingException.Reference> path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            String name = path.get(i).getFieldName();
            if (name != null) {
                return safe(name);
            }
        }
        return "arguments";
    }

    private static String safe(String name) {
        if (name == null) {
            return "";
        }
        String printable = name.codePoints().filter(c -> !Character.isISOControl(c))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return printable.length() > MAX_FIELD_NAME_LENGTH ? printable.substring(0, MAX_FIELD_NAME_LENGTH) : printable;
    }
}
