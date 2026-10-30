package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Validates the researcher-visible state -- {@code query}, {@code facets}, {@code search} -- in both directions with one set of checks: the
 * state arriving on {@code POST /ai/chat} and the state the model proposes through {@code propose_query}.
 *
 * <p>Checks are structural (types, required fields, bounds). Whether a concept path or facet actually exists is not decided here: for the
 * model's output, that is {@link ConceptPaths} and {@link KnownFacets}, which require it to have come back from a tool result this turn.
 */
final class ChatStateValidator {

    /** Most facets accepted in one selection. */
    static final int MAX_FACETS = 100;

    /** Longest facet category or name accepted, in characters. */
    static final int MAX_FACET_TEXT_LENGTH = 500;

    /** Longest {@code search} text accepted, in characters. Generous: real terms are a few words. */
    static final int MAX_SEARCH_LENGTH = 1_000;

    private static final ObjectMapper CANONICAL = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ChatStateValidator() {}

    /**
     * Validates the state on an inbound chat request.
     *
     * @param request the request
     * @return the request's {@code query}, bound and validated, or null if it has none
     * @throws PicsureException with status 400 naming the field when any of the three is malformed
     */
    static ProposeQueryInput requireValid(ChatRequest request) {
        ProposeQueryInput query;
        try {
            query = query(request.query());
        } catch (IllegalArgumentException e) {
            throw invalid("query", e);
        }
        try {
            requireValidFacets(request.facets());
        } catch (IllegalArgumentException e) {
            throw invalid("facets", e);
        }
        try {
            requireValidSearch(request.search());
        } catch (IllegalArgumentException e) {
            throw invalid("search", e);
        }
        return query;
    }

    /**
     * Binds and validates a client-supplied {@code query}, tolerating the UI's extra fields (see {@link ProposeQueryBinder#bindClientQuery}).
     *
     * @param query the query node, possibly null or JSON null
     * @return the validated input, or null if absent
     * @throws IllegalArgumentException naming what is wrong
     */
    static ProposeQueryInput query(JsonNode query) {
        if (isAbsent(query)) {
            return null;
        }
        if (!query.isObject()) {
            throw new IllegalArgumentException("It must be a JSON object.");
        }
        ProposeQueryInput bound = ProposeQueryBinder.bindClientQuery(query);
        bound.validate();
        return bound;
    }

    /**
     * @param facets a facet selection, possibly null
     * @throws IllegalArgumentException if it is oversized or an entry lacks a bounded, non-blank {@code category} or {@code name}
     */
    static void requireValidFacets(List<FacetSelection> facets) {
        if (facets == null) {
            return;
        }
        if (facets.size() > MAX_FACETS) {
            throw new IllegalArgumentException("At most " + MAX_FACETS + " facets are accepted.");
        }
        for (FacetSelection facet : facets) {
            if (facet == null || isBlank(facet.category()) || isBlank(facet.name())) {
                throw new IllegalArgumentException("Each facet requires non-blank 'category' and 'name'.");
            }
            if (facet.category().length() > MAX_FACET_TEXT_LENGTH || facet.name().length() > MAX_FACET_TEXT_LENGTH) {
                throw new IllegalArgumentException("A facet 'category' or 'name' is too long.");
            }
            requireCleanText(facet.category());
            requireCleanText(facet.name());
        }
    }

    /**
     * The search text and facets travel to the same place ({@code /dictionary}), and the UI doesn't validate either, so both get the same
     * harmful-content check. Search terms are assumed to be ordinary words: the check refuses control and invisible-formatting characters
     * and an unreasonable length, and nothing finer. A blank search is valid -- it clears the search.
     *
     * @param search the search text, possibly null
     * @throws IllegalArgumentException if it is too long or holds control or formatting characters
     */
    static void requireValidSearch(String search) {
        if (search == null) {
            return;
        }
        if (search.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("It is too long.");
        }
        requireCleanText(search);
    }

    /**
     * @param text text bound for the dictionary
     * @throws IllegalArgumentException if it holds a control character (newlines, NUL, escapes...) or an invisible formatting one
     *         (zero-width, bidirectional override...), the usual vehicles for smuggling content past a reader
     */
    static void requireCleanText(String text) {
        boolean harmful = text.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
        if (harmful) {
            throw new IllegalArgumentException("It must not contain control or invisible formatting characters.");
        }
    }

    /**
     * @param query a validated query input
     * @return its JSON form, without null fields -- the canonical form both compared against the request and returned to the UI
     */
    static JsonNode toJson(ProposeQueryInput query) {
        return CANONICAL.valueToTree(query);
    }

    /**
     * @param node a request field that may be missing or an explicit JSON null
     * @return whether there is nothing there
     */
    static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static PicsureException invalid(String field, IllegalArgumentException cause) {
        return new PicsureException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid '" + field + "': " + cause.getMessage());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
