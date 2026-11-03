package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Keeps the researcher's filters, applies the proposed changes to their selected variables, adds the facets a proposed search was run
     * with, and drops search terms that matched nothing.
     *
     * @param proposal a proposal that passed {@link ProposeQueryTool}'s checks
     * @param state this turn's state; the adjusted proposal is accepted into it
     * @return the model-facing result: what the researcher will see, or why the proposal was refused
     */
    static ToolResult apply(Proposal proposal, TurnState state) {
        // An earlier proposal this turn is what the researcher will see, so a later one changes that.
        ProposeQueryInput before = state.query != null ? state.query : state.currentQuery;
        if (proposal.query() != null && !hasFilters(proposal.query())) {
            proposal = keepingFilters(proposal, state, before);
            if (proposal == null) {
                return ToolResult.failure(NO_FILTERS_TO_KEEP);
            }
        }
        String selectNote = null;
        if (proposal.query() != null) {
            ProposeQueryInput query = proposal.query();
            List<String> selected = selectedAfter(before, query.select(), proposal.removeSelect());
            proposal = proposal.withQuery(new ProposeQueryInput(selected, query.phenotypicClause(), query.genomicFilters()));
            // The researcher's own selection plus the additions can be over the limit though the proposal alone was not.
            if (proposal.query().exceedsSizeLimit()) {
                return ToolResult.failure(ProposeQueryTool.QUERY_TOO_LARGE);
            }
            selectNote = selectNote(proposal.query(), before, proposal.removeSelect());
        }
        return acceptDroppingUnmatchedTerms(withSearchFacets(proposal, state.searches), state, selectNote);
    }

    /**
     * Dropping the current filters would silently change the cohort; clearing them is the UI reset button's job.
     *
     * @return the proposal with the current filters; as it is when there are none but it changes the researcher's selected variables; as
     *         a search when it only names variables and the researcher has none selected; null when there is no search to fall back on
     */
    private static Proposal keepingFilters(Proposal proposal, TurnState state, ProposeQueryInput before) {
        ProposeQueryInput keep = hasFilters(state.query) ? state.query : state.currentQuery;
        if (hasFilters(keep)) {
            return proposal.withQuery(new ProposeQueryInput(proposal.query().select(), keep.phenotypicClause(), keep.genomicFilters()));
        }
        if (hasSelected(before) || !proposal.removeSelect().isEmpty()) {
            return proposal;
        }
        // No filters and nothing selected: a lookup the model wrote as a query.
        String lookup = proposal.search() != null ? proposal.search() : state.searches.lastMatchedSearchWithoutMisses();
        return lookup == null || lookup.isBlank() ? null : proposal.withQuery(null).withSearch(lookup);
    }

    /**
     * The researcher's selected variables, then the added ones, without the removed ones. Empty, not null, when a removal leaves none:
     * the response leaves a null select out, and the UI would keep the old one.
     */
    private static List<String> selectedAfter(ProposeQueryInput before, List<String> added, List<String> removed) {
        Map<String, String> selected = new LinkedHashMap<>();
        if (hasSelected(before)) {
            before.select().forEach(path -> selected.putIfAbsent(ConceptPaths.normalize(path), path));
        }
        if (added != null) {
            added.forEach(path -> selected.putIfAbsent(ConceptPaths.normalize(path), path));
        }
        removed.forEach(path -> selected.remove(ConceptPaths.normalize(path)));
        return selected.isEmpty() && removed.isEmpty() ? null : List.copyOf(selected.values());
    }

    /**
     * The variables the researcher will see, by name, so the reply can say what changed. A removal that did not happen is called out:
     * otherwise the model says it was made.
     */
    private static String selectNote(ProposeQueryInput query, ProposeQueryInput before, List<String> removed) {
        Set<String> filtered = new LinkedHashSet<>();
        if (query.phenotypicClause() != null) {
            query.phenotypicClause().addPathsTo(filtered);
        }
        Set<String> wasSelected = new LinkedHashSet<>();
        if (hasSelected(before)) {
            before.select().forEach(path -> wasSelected.add(ConceptPaths.normalize(path)));
        }
        Map<String, String> shown = new LinkedHashMap<>();
        for (Collection<String> paths : List.of(query.select() == null ? List.<String>of() : query.select(), filtered)) {
            paths.forEach(path -> shown.putIfAbsent(ConceptPaths.normalize(path), path));
        }
        List<String> notes = new ArrayList<>();
        notes.add(shown.isEmpty() ? "They have no selected variables." : "Their selected variables are now: " + names(shown.values()) + ".");
        List<String> filteredRemovals = removed.stream()
            .filter(path -> filtered.stream().anyMatch(f -> ConceptPaths.normalize(f).equals(ConceptPaths.normalize(path)))).toList();
        if (!filteredRemovals.isEmpty()) {
            notes.add(names(filteredRemovals) + " stayed selected, because the query filters on it; the table always shows filtered "
                + "variables.");
        }
        List<String> notSelected = removed.stream().filter(path -> !wasSelected.contains(ConceptPaths.normalize(path))).toList();
        if (!notSelected.isEmpty()) {
            notes.add(names(notSelected) + " was not selected, so there was nothing to remove.");
        }
        return String.join(" ", notes);
    }

    /** Each path's last folder, which is how the researcher's table names the variable. */
    private static String names(Collection<String> paths) {
        return String.join(", ", paths.stream().map(path -> ConceptPaths.segments(path).getLast()).toList());
    }

    /** Without the facets the model searched with, the table would show other datasets' variables. */
    private static Proposal withSearchFacets(Proposal proposal, SearchHistory searches) {
        if (proposal.facets() != null || proposal.query() != null) {
            return proposal;
        }
        List<FacetSelection> narrowing = searches.facetsForSearch(proposal.search());
        return narrowing == null ? proposal : proposal.withFacets(narrowing);
    }

    private static ToolResult acceptDroppingUnmatchedTerms(Proposal proposal, TurnState state, String selectNote) {
        SearchHistory.TermSplit split = state.searches.splitUnmatched(proposal.search());
        if (split.dropped().isEmpty()) {
            state.accept(proposal);
            return accepted(proposal, null, selectNote);
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
        String note = "Dropped " + String.join(", ", split.dropped()) + " from the search because nothing matched. The researcher's "
            + "table shows only: " + keptSearch + ".";
        return accepted(trimmed, note, selectNote);
    }

    /** Says what actually changed: given a bare {@code received}, the model claimed changes that weren't made. */
    private static ToolResult accepted(Proposal proposal, String note, String selectNote) {
        ObjectNode result = JSON.createObjectNode().put("received", true);
        if (note != null) {
            result.put("note", note);
        }
        List<String> applied = new ArrayList<>();
        if (proposal.query() != null) {
            applied.add("Their query is updated. " + selectNote);
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

    private static boolean hasSelected(ProposeQueryInput query) {
        return query != null && query.select() != null && !query.select().isEmpty();
    }

    private static boolean hasFilters(ProposeQueryInput query) {
        return query != null && (query.phenotypicClause() != null || query.genomicFilters() != null && !query.genomicFilters().isEmpty());
    }
}
