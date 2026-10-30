package edu.harvard.hms.dbmi.avillach.commons.binding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Binds a tool call's arguments to an input record strictly. An unknown field fails instead of being silently dropped, and every binding
 * error becomes a one-sentence, model-facing message, never Jackson's own. The exception type that carries the message is the caller's
 * choice.
 */
public final class StrictArgumentBinder {

    /** Longest field name echoed back in a failure message. */
    public static final int MAX_FIELD_NAME_LENGTH = 64;

    private final ObjectMapper mapper;
    private final Class<?> clauseType;
    private final Function<String, ? extends RuntimeException> failure;

    /**
     * Creates a binder.
     *
     * @param clauseType the polymorphic clause type whose unresolvable type id gets the clause-shape message
     * @param refuseEnumIndexes whether an enum given by its index, as a number or numeric string, is refused
     * @param failure builds the exception thrown for a message
     */
    public StrictArgumentBinder(Class<?> clauseType, boolean refuseEnumIndexes, Function<String, ? extends RuntimeException> failure) {
        JsonMapper.Builder builder = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        if (refuseEnumIndexes) {
            builder.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        }
        this.mapper = builder.build();
        this.clauseType = clauseType;
        this.failure = failure;
    }

    /**
     * Binds a call's arguments to a tool's input record.
     *
     * @param arguments the call's arguments, possibly null
     * @param type the input record
     * @param <T> the input type
     * @return the bound input
     * @throws RuntimeException built by the failure function, naming the offending field when an argument is unknown, of the wrong type,
     *         or not a valid clause
     */
    public <T> T bind(Map<String, Object> arguments, Class<T> type) {
        try {
            return mapper.convertValue(arguments == null ? Map.of() : arguments, type);
        } catch (IllegalArgumentException e) {
            throw failure.apply(e.getCause() instanceof JsonMappingException mapping ? describe(mapping) : unreadable());
        }
    }

    private String describe(JsonMappingException e) {
        if (e instanceof UnrecognizedPropertyException unknown) {
            return "Field '" + safe(unknown.getPropertyName()) + "' is not part of this tool's input.";
        }
        if (e instanceof InvalidTypeIdException typeId && typeId.getBaseType() != null && typeId.getBaseType().hasRawClass(clauseType)) {
            return "Each phenotypic clause must be a filter (phenotypicFilterType and conceptPath) or a subquery (operator and "
                + "phenotypicClauses).";
        }
        String field = lastField(e.getPath());
        if (e instanceof InvalidFormatException format && format.getTargetType() != null && format.getTargetType().isEnum()) {
            String allowed =
                Arrays.stream(format.getTargetType().getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
            return "Field '" + field + "' must be one of " + allowed + ".";
        }
        if (e instanceof MismatchedInputException) {
            return "Field '" + field + "' has the wrong type.";
        }
        return unreadable();
    }

    private static String unreadable() {
        return "The arguments could not be read. Check them against the tool's input schema.";
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
