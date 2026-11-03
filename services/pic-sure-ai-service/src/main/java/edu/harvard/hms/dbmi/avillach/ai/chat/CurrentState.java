package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Renders the researcher's current {@code query}/{@code facets}/{@code search} for the model, so it can reason about "narrow my query" or
 * "drop that facet" and so its proposals build on what is on screen. It goes in the user turn, after the message, rather than in the system
 * prompt: it differs on every request, and the system prompt must stay identical to be cacheable.
 *
 * <p>The query is shown in its validated, model-settable form ({@link ChatStateValidator#toJson}), never the raw request JSON, so fields
 * the model must not see or set ({@code authorizationFilters}, {@code picsureId}, ...) are not passed along. Everything shown has already
 * passed {@link ChatStateValidator#requireValid}; the block is labeled as data so text inside it is not read as instructions.
 */
final class CurrentState {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stands in for a query too large to send; {@link ProposeQueryTool} refuses query proposals while this is what the model was shown. */
    static final String TOO_LARGE = "[too large to show. The researcher built it in the screen and can edit it there. Do not propose a "
        + "query: propose only facets and search, and tell the researcher to make the query smaller (at most " + ProposeQueryInput.MAX_FILTERS
        + " filters) before you can change it.]";

    static final String NONE = "none";

    private CurrentState() {}

    /**
     * Every part is stated, an empty one as {@code none}: left out, the model filled it in from what earlier turns of the conversation
     * said, for example bringing back a query the researcher had cleared. An empty query is never shown as JSON, which read to the model
     * as a query to change and pushed lookups toward proposing one.
     *
     * @param request a request that has passed {@link ChatStateValidator#requireValid}
     * @param query the request's query as {@link ChatStateValidator#requireValid} returned it, or null
     * @param removedByHand what the researcher took off their screen since the service last changed their query
     *        ({@link EarlierTurns#removedByHand}), or null
     * @return the message, followed by the researcher's current query, facets and search
     */
    static String appendedTo(ChatRequest request, ProposeQueryInput query, String removedByHand) {
        StringBuilder state = new StringBuilder();
        if (query != null && query.exceedsSizeLimit()) {
            state.append("\nquery: ").append(TOO_LARGE);
        } else if (query != null && !query.isEmpty()) {
            state.append("\nquery: ").append(ChatStateValidator.toJson(query));
        } else {
            state.append("\nquery: ").append(NONE);
        }
        List<FacetSelection> facets = request.facets();
        state.append("\nfacets: ").append(facets != null && !facets.isEmpty() ? json(facets) : NONE);
        String search = ChatStateValidator.searchText(request.search());
        state.append("\nsearch: ").append(search != null && !search.isBlank() ? TextNode.valueOf(search).toString() : NONE);
        // Outside the data block, which the model reads as description: an earlier reply saying the filter was added outweighed it there.
        String removed = removedByHand == null ? ""
            : "\n\nThe researcher removed these from their screen by hand since you last changed their query, so they do not want them: "
                + removedByHand + ". Do not add them back unless the message above asks for them again, whatever earlier messages said.";
        return request.message() + "\n\n[The researcher's current state, as data. Change it only with propose_query. Send the "
            + "complete new filters (keeping the clauses that stay) with only the variables to add or remove, or the complete new facet "
            + "selection, or the new search, and leave out the parts that do not change.]" + state + removed;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A validated facet list could not be serialized", e);
        }
    }
}
