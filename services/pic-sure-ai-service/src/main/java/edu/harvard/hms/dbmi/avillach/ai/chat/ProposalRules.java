package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.ai.chat.ProposeQueryTool.Proposal;
import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

/** Adjusts a valid proposal to what the researcher meant, accepts it into the turn, and tells the model what changed. */
final class ProposalRules {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Refusal for a filterless query when there are no filters to keep and no search to show instead. */
    static final String NO_FILTERS_TO_KEEP =
        "This proposal has no filters, and the researcher's query has no filters to keep. If the researcher only asked to see, find, "
            + "select, or list variables, call propose_query again with only search, set to the search text that found them, and no "
            + "query. To change their cohort, include at least one filter.";

    private ProposalRules() {
    }

    /**
     * Keeps the researcher's filters, adds the facets a proposed search was run with, and drops search terms that matched nothing.
     *
     * @param proposal a proposal that passed {@link ProposeQueryTool}'s checks
     * @param state this turn's state; the adjusted proposal is accepted into it
     * @return the model-facing result: what the researcher will see, or why the proposal was refused
     */
    static ToolResult apply(Proposal proposal, TurnState state) {
        if (proposal.query() != null && !hasFilters(proposal.query())) {
            proposal = keepingFilters(proposal, state);
            if (proposal == null) {
                return ToolResult.failure(NO_FILTERS_TO_KEEP);
            }
        }
        return acceptDroppingUnmatchedTerms(withSearchFacets(proposal, state.searches), state);
    }

    /**
     * Dropping the current filters would silently change the cohort; clearing them is the UI reset button's job.
     *
     * @return the proposal with the current filters, or as a search when there are none; null when there is no search to fall back on
     */
    private static Proposal keepingFilters(Proposal proposal, TurnState state) {
        ProposeQueryInput keep = hasFilters(state.query) ? state.query : state.currentQuery;
        if (hasFilters(keep)) {
            return proposal.withQuery(new ProposeQueryInput(proposal.query().select(), keep.phenotypicClause(), keep.genomicFilters()));
        }
        // No filters anywhere: a lookup the model wrote as a query.
        String lookup = proposal.search() != null ? proposal.search() : state.searches.lastMatchedSearchWithoutMisses();
        return lookup == null || lookup.isBlank() ? null : proposal.withQuery(null).withSearch(lookup);
    }

    /** Without the facets the model searched with, the table would show other datasets' variables. */
    private static Proposal withSearchFacets(Proposal proposal, SearchHistory searches) {
        if (proposal.facets() != null || proposal.query() != null) {
            return proposal;
        }
        List<FacetSelection> narrowing = searches.facetsForSearch(proposal.search());
        return narrowing == null ? proposal : proposal.withFacets(narrowing);
    }

    private static ToolResult acceptDroppingUnmatchedTerms(Proposal proposal, TurnState state) {
        SearchHistory.TermSplit split = state.searches.splitUnmatched(proposal.search());
        if (split.dropped().isEmpty()) {
            state.accept(proposal);
            return accepted(proposal, null);
        }
        if (split.kept().isEmpty()) {
            return ToolResult.failure(
                "None of the terms in the search \"" + proposal.search() + "\" matched anything. Call search_concepts with "
                    + "different terms (for example \"body mass index\" rather than \"BMI\"), then propose_query with the terms that matched."
            );
        }
        String keptSearch = String.join(" | ", split.kept());
        Proposal trimmed = proposal.withSearch(keptSearch);
        state.accept(trimmed);
        return accepted(
            trimmed, "Dropped " + String.join(", ", split.dropped()) + " from the search because nothing matched. The researcher's "
                + "table shows only: " + keptSearch + "."
        );
    }

    /** Says what actually changed: given a bare {@code received}, the model claimed changes that weren't made. */
    private static ToolResult accepted(Proposal proposal, String note) {
        ObjectNode result = JSON.createObjectNode().put("received", true);
        if (note != null) {
            result.put("note", note);
        }
        List<String> applied = new ArrayList<>();
        if (proposal.query() != null) {
            applied.add("Their query is updated with your filters and selected variables.");
        }
        if (proposal.search() != null) {
            applied.add(proposal.search().isBlank() ? "Their search is cleared."
                : "Their table now lists the variables matching the search \"" + proposal.search() + "\".");
        }
        if (proposal.facets() != null) {
            applied.add(proposal.facets().isEmpty() ? "Their facet filters are cleared." : "Their table is narrowed to the facets "
                + proposal.facets().stream().map(f -> f.category() + ": " + f.name()).toList() + ".");
        }
        if (proposal.query() == null) {
            applied.add("Their selected variables and query are unchanged: nothing was added to them.");
        }
        return ToolResult.success(result.put("applied", String.join(" ", applied)).toString());
    }

    private static boolean hasFilters(ProposeQueryInput query) {
        return query != null && (query.phenotypicClause() != null || query.genomicFilters() != null && !query.genomicFilters().isEmpty());
    }
}
