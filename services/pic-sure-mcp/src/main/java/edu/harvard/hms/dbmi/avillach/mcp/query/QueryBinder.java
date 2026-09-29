package edu.harvard.hms.dbmi.avillach.mcp.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Binds a query tool's arguments strictly. Spring AI does not validate arguments against the input schema, and its own binder drops unknown
 * fields, which would silently discard a {@code not: true} and invert a filter. This binder fails on any unknown field instead and turns
 * every binding error into a one-sentence {@link ToolFailure}, never Jackson's message.
 */
public final class QueryBinder {

    /** Longest field name echoed back in a failure message. */
    static final int MAX_FIELD_NAME_LENGTH = 64;

    private static final ObjectMapper STRICT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    private QueryBinder() {}

    /**
     * Binds the arguments of a {@code tools/call} to a tool's input record.
     *
     * @param arguments the call's arguments, possibly null
     * @param type the input record
     * @param <T> the input type
     * @return the bound input
     * @throws ToolFailure naming the offending field when an argument is unknown, of the wrong type, or not a valid clause
     */
    public static <T> T bind(Map<String, Object> arguments, Class<T> type) {
        try {
            return STRICT.convertValue(arguments == null ? Map.of() : arguments, type);
        } catch (IllegalArgumentException e) {
            throw e.getCause() instanceof JsonMappingException mapping ? describe(mapping) : unreadable();
        }
    }

    private static ToolFailure describe(JsonMappingException e) {
        if (e instanceof UnrecognizedPropertyException unknown) {
            return new ToolFailure("Field '" + safe(unknown.getPropertyName()) + "' is not part of this tool's input.");
        }
        if (
            e instanceof InvalidTypeIdException typeId && typeId.getBaseType() != null
                && typeId.getBaseType().hasRawClass(QueryInput.Clause.class)
        ) {
            return new ToolFailure(
                "Each phenotypic clause must be a filter (phenotypicFilterType and conceptPath) or a subquery (operator and phenotypicClauses)."
            );
        }
        String field = lastField(e.getPath());
        if (e instanceof InvalidFormatException format && format.getTargetType() != null && format.getTargetType().isEnum()) {
            String allowed =
                Arrays.stream(format.getTargetType().getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
            return new ToolFailure("Field '" + field + "' must be one of " + allowed + ".");
        }
        if (e instanceof MismatchedInputException) {
            return new ToolFailure("Field '" + field + "' has the wrong type.");
        }
        return unreadable();
    }

    private static ToolFailure unreadable() {
        return new ToolFailure("The arguments could not be read. Check them against the tool's input schema.");
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
