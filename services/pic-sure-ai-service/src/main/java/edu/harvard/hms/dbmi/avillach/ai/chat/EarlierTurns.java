package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import edu.harvard.hms.dbmi.avillach.ai.chat.ConversationTurn.ToolOutput;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.AssistantToolCallEntry;
import edu.harvard.hms.dbmi.avillach.ai.model.ConversationEntry.UserEntry;

/**
 * What a turn takes from the earlier turns of its conversation. The model sees each earlier message and reply, not the lookups behind them:
 * turns already take up to 38k input tokens, and replaying every result would multiply that. The stored results instead rebuild what the
 * guards have seen, so a path or facet an earlier lookup returned can be used again without searching for it. If follow-ups keep searching
 * again for results the model can no longer see, adding a short digest of each result in {@link #entries} is the next step.
 */
final class EarlierTurns {

    private EarlierTurns() {}

    /** @return each turn's message and reply, oldest first, for the model to read before the current message */
    static List<ConversationEntry> entries(List<ConversationTurn> turns) {
        List<ConversationEntry> entries = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            entries.add(new UserEntry(turn.message()));
            entries.add(new AssistantToolCallEntry(turn.reply(), List.of()));
        }
        return entries;
    }

    /**
     * What the researcher took off their screen by hand: filters and selected variables the last query the service sent them had, but the
     * request's query no longer does. Told only that their screen lacks them, the model added a filter back because an earlier message had
     * asked for it.
     *
     * @param current the request's validated query, or null
     * @return e.g. {@code filters on `\a\`; selected variables `\a\`}, or null when nothing was removed or no query was ever sent
     */
    static String removedByHand(List<ConversationTurn> turns, ProposeQueryInput current, int maxFilters) {
        Removed removed = removed(turns, current, maxFilters);
        List<String> parts = new ArrayList<>();
        if (!removed.filters().isEmpty()) {
            parts.add("filters on " + backticked(removed.filters()));
        }
        if (!removed.selected().isEmpty()) {
            parts.add("selected variables " + backticked(removed.selected()));
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * @param turns the conversation's earlier turns
     * @param current the request's validated query, or null
     * @return every path {@link #removedByHand} names, filters and selected variables together, normalized; empty when none
     */
    static Set<String> removedPathsByHand(List<ConversationTurn> turns, ProposeQueryInput current, int maxFilters) {
        Removed removed = removed(turns, current, maxFilters);
        Set<String> paths = new LinkedHashSet<>(removed.filters());
        paths.addAll(removed.selected());
        return paths;
    }

    private record Removed(Set<String> filters, Set<String> selected) {
    }

    private static Removed removed(List<ConversationTurn> turns, ProposeQueryInput current, int maxFilters) {
        JsonNode sent = null;
        for (ConversationTurn turn : turns) {
            sent = turn.sentQuery() != null ? turn.sentQuery() : sent;
        }
        if (sent == null || (current != null && current.exceedsSizeLimit(maxFilters))) {
            return new Removed(Set.of(), Set.of());
        }
        JsonNode now = current == null ? MissingNode.getInstance() : ChatStateValidator.toJson(current);
        Set<String> filters = filterPaths(sent.path("phenotypicClause"));
        filters.removeAll(filterPaths(now.path("phenotypicClause")));
        Set<String> selected = selectPaths(sent);
        selected.removeAll(selectPaths(now));
        return new Removed(filters, selected);
    }

    private static Set<String> filterPaths(JsonNode clause) {
        Set<String> paths = new LinkedHashSet<>();
        if (clause.path("conceptPath").isTextual()) {
            paths.add(ConceptPaths.normalize(clause.path("conceptPath").textValue()));
        }
        clause.path("phenotypicClauses").forEach(child -> paths.addAll(filterPaths(child)));
        return paths;
    }

    private static Set<String> selectPaths(JsonNode query) {
        Set<String> paths = new LinkedHashSet<>();
        query.path("select").forEach(path -> {
            if (path.isTextual()) {
                paths.add(ConceptPaths.normalize(path.textValue()));
            }
        });
        return paths;
    }

    private static String backticked(Set<String> paths) {
        return paths.stream().map(path -> "`" + path + "`").collect(Collectors.joining(", "));
    }

    /**
     * Carries over only what describes the dictionary: paths, facets, and terms that matched nothing. What describes one turn's reply (the
     * counted query, the searches that matched, whether facets were looked up, repeated failures) starts fresh, so nothing is proposed in a
     * later turn because of an earlier one.
     */
    static void learnInto(TurnState state, List<ConversationTurn> turns) {
        for (ConversationTurn turn : turns) {
            for (ToolOutput output : turn.toolResults()) {
                state.conceptPaths.learnFrom(output.content());
                if (ToolUseLoopService.SEARCH_CONCEPTS.equals(output.toolName())) {
                    state.searches.learnUnmatchedTerms(
                        ToolUseLoopService.readJson(output.argumentsJson()), ToolUseLoopService.readJson(output.content())
                    );
                }
                if (ToolUseLoopService.LIST_FACETS.equals(output.toolName())) {
                    state.knownFacets.learnFromListFacets(output.content());
                }
            }
        }
    }
}
