package edu.harvard.hms.dbmi.avillach.mcp.query;

import edu.harvard.hms.dbmi.avillach.commons.binding.StrictArgumentBinder;
import edu.harvard.hms.dbmi.avillach.mcp.tool.ToolFailure;

import java.util.Map;

/**
 * Binds a query tool's arguments strictly. Spring AI does not validate arguments against the input schema, and its own binder drops unknown
 * fields, which would silently discard a {@code not: true} and invert a filter. This binder fails on any unknown field instead, refuses an
 * enum given by its index, as a number or a numeric string, so {@code "language": 2} cannot pick a language by position, and turns every
 * binding error into a one-sentence {@link ToolFailure}, never Jackson's message.
 */
public final class QueryBinder {

    /** Longest field name echoed back in a failure message. */
    static final int MAX_FIELD_NAME_LENGTH = StrictArgumentBinder.MAX_FIELD_NAME_LENGTH;

    private static final StrictArgumentBinder BINDER = new StrictArgumentBinder(QueryInput.Clause.class, true, ToolFailure::new);

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
        return BINDER.bind(arguments, type);
    }
}
