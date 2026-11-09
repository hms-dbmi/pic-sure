package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import edu.harvard.hms.dbmi.avillach.commons.binding.StrictArgumentBinder;

/**
 * Binds {@code propose_query}'s arguments strictly, mirroring {@code pic-sure-mcp}'s own {@code query.QueryBinder}: an unknown field fails
 * instead of being silently dropped (which would hide a disallowed field, such as {@code not}, rather than rejecting it), and every binding
 * error becomes a one-sentence, model-facing message instead of a raw Jackson exception.
 */
final class ProposeQueryBinder {

    /** Longest field name echoed back in a failure message. */
    static final int MAX_FIELD_NAME_LENGTH = StrictArgumentBinder.MAX_FIELD_NAME_LENGTH;

    private static final StrictArgumentBinder BINDER =
        new StrictArgumentBinder(ProposeQueryInput.Clause.class, false, IllegalArgumentException::new);

    private ProposeQueryBinder() {}

    /**
     * Binds the request's current {@code query} to its input record, ignoring fields the record doesn't model. This is the researcher's own
     * query: the UI's full query object carries fields the model may not set ({@code authorizationFilters}, {@code expectedResultType},
     * {@code not}, ...), and those must not make a legitimate request fail. Everything the record models is still bound and type-checked.
     *
     * @param query the query object from the request
     * @return the bound input
     * @throws IllegalArgumentException naming the offending field when a modeled field is of the wrong type or not a valid clause
     */
    static ProposeQueryInput bindClientQuery(JsonNode query) {
        return BINDER.bindIgnoringUnknown(query, ProposeQueryInput.class);
    }

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
        return BINDER.bind(arguments, type);
    }
}
