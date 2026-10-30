package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** What one chat turn has learned and accepted so far. A later proposal replaces an earlier one only for the parts it sends. */
final class TurnState {

    /** Concept paths seen in the request or in a tool result; only these may be used or proposed. */
    final ConceptPaths conceptPaths = new ConceptPaths();
    /** Facets seen in the request or in a list_facets result; only these may be used or proposed. */
    final KnownFacets knownFacets = new KnownFacets();
    final SearchHistory searches = new SearchHistory();
    /** Each tool's failures, keyed by tool name and exact error text. */
    final Map<String, Integer> failureCounts = new HashMap<>();
    /** The query the request arrived with, or null. */
    final ProposeQueryInput currentQuery;

    /** The accepted proposal so far; each part null until a proposal sends it. */
    ProposeQueryInput query;
    List<FacetSelection> facets;
    String search;

    /** Whether list_facets has run this turn, called by the model or looked up by the loop. */
    boolean facetsListed;
    /** Set once a tool fails the same way too often; later tool calls are answered without running. */
    boolean toolsStopped;
    /** Whether the model was already asked to fix unknown paths in its reply; asked at most once. */
    boolean pathRepairAsked;
    /** The query of the latest successful count_participants call, or null. */
    JsonNode lastCountedQuery;

    /** @param currentQuery the query the request arrived with, or null */
    TurnState(ProposeQueryInput currentQuery) {
        this.currentQuery = currentQuery;
    }

    /** @param arguments a successful count_participants call's arguments */
    void learnCountedQuery(JsonNode arguments) {
        if (arguments.path("query").isObject()) {
            lastCountedQuery = arguments.path("query");
        }
    }

    /** @return whether any part of a proposal has been accepted this turn */
    boolean hasProposal() {
        return query != null || facets != null || search != null;
    }

    /** @param proposal a checked proposal; its non-null parts replace what was accepted before */
    void accept(ProposeQueryTool.Proposal proposal) {
        query = proposal.query() != null ? proposal.query() : query;
        facets = proposal.facets() != null ? proposal.facets() : facets;
        search = proposal.search() != null ? proposal.search() : search;
    }
}
