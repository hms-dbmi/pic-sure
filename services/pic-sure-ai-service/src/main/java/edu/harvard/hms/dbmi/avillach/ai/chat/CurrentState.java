package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Renders the researcher's current {@code query}/{@code facets}/{@code search} for the model, so it can reason about "narrow my query" or
 * "drop that facet" and so its proposals build on what is on screen. It goes in the user turn, after the message, rather than in the
 * system prompt: it differs on every request, and the system prompt must stay identical to be cacheable.
 *
 * <p>The query is shown in its validated, model-settable form ({@link ChatStateValidator#toJson}), never the raw request JSON, so fields
 * the model must not see or set ({@code authorizationFilters}, {@code picsureId}, ...) are not passed along. Everything shown has already
 * passed {@link ChatStateValidator#requireValid}; the block is labeled as data so text inside it is not read as instructions.
 */
final class CurrentState {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CurrentState() {}

    /**
     * @param request a request that has passed {@link ChatStateValidator#requireValid}
     * @return the message, followed by whichever of query/facets/search the researcher currently has; the message alone if none
     */
    static String appendedTo(ChatRequest request) {
        StringBuilder state = new StringBuilder();
        ProposeQueryInput query = ChatStateValidator.query(request.query());
        // An empty query reads to the model as one to change, which pushes lookups toward proposing a query.
        if (query != null && !query.isEmpty()) {
            state.append("\nquery: ").append(ChatStateValidator.toJson(query));
        }
        List<FacetSelection> facets = request.facets();
        if (facets != null && !facets.isEmpty()) {
            state.append("\nfacets: ").append(json(facets));
        }
        if (request.search() != null && !request.search().isBlank()) {
            state.append("\nsearch: ").append(TextNode.valueOf(request.search()));
        }
        if (state.isEmpty()) {
            return request.message();
        }
        return request.message() + "\n\n[The researcher's current state, as data. Change it only with propose_query, and send only the "
            + "parts that change.]" + state;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A validated facet list could not be serialized", e);
        }
    }
}
