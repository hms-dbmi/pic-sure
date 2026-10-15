package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/** What this turn's search_concepts calls found: which terms matched nothing, and the latest search that found something. */
final class SearchHistory {

    /** Lower case. */
    private final Set<String> unmatchedTerms = new HashSet<>();
    private String lastMatchedSearch;
    private List<FacetSelection> lastMatchedFacets = List.of();
    private int matchedSearches;

    /**
     * @param arguments a successful search_concepts call's arguments
     * @param result its result
     */
    void learn(JsonNode arguments, JsonNode result) {
        learnUnmatchedTerms(arguments, result);
        learnLastMatchedSearch(arguments, result);
    }

    private void learnUnmatchedTerms(JsonNode arguments, JsonNode result) {
        JsonNode totals = result.path("termTotals");
        if (totals.isObject()) {
            totals.properties().forEach(e -> record(e.getKey(), e.getValue().asInt(0) > 0));
            return;
        }
        JsonNode query = arguments.path("query");
        if (query.isTextual() && !query.asText().contains("|") && result.path("total").isInt()) {
            record(query.asText(), result.path("total").asInt() > 0);
        }
    }

    private void record(String term, boolean matched) {
        String key = term.trim().toLowerCase(Locale.ROOT);
        if (matched) {
            unmatchedTerms.remove(key);
        } else if (!key.isEmpty()) {
            unmatchedTerms.add(key);
        }
    }

    private void learnLastMatchedSearch(JsonNode arguments, JsonNode result) {
        JsonNode query = arguments.path("query");
        JsonNode concepts = result.path("concepts");
        List<FacetSelection> narrowing = FacetSelection.listedIn(arguments);
        boolean hasText = query.isTextual() && !query.asText().isBlank();
        boolean blankText = query.isMissingNode() || query.isTextual() && query.asText().isBlank();
        // A facet-only browse has no search text but still counts as a matched search.
        boolean browse = blankText && !narrowing.isEmpty();
        if ((hasText || browse) && concepts.isArray() && !concepts.isEmpty()) {
            matchedSearches++;
            lastMatchedSearch = query.isTextual() ? query.asText() : "";
            lastMatchedFacets = narrowing;
        }
    }

    /**
     * @param kept terms that matched something, or were never searched
     * @param dropped terms that matched nothing
     */
    record TermSplit(List<String> kept, List<String> dropped) {
    }

    /**
     * @param search {@code |}-joined search text, or null
     * @return its terms, split by whether they matched anything this turn
     */
    TermSplit splitUnmatched(String search) {
        Map<Boolean, List<String>> byMiss = TermBalancedSearch.terms(search).stream()
            .collect(Collectors.partitioningBy(term -> unmatchedTerms.contains(term.toLowerCase(Locale.ROOT))));
        return new TermSplit(byMiss.get(false), byMiss.get(true));
    }

    /**
     * @param search proposed search text, or null for a facet-only browse
     * @return the facets the latest matching search was narrowed by, if it covered every term of {@code search}; else null
     */
    List<FacetSelection> facetsForSearch(String search) {
        if (lastMatchedFacets.isEmpty() || lastMatchedSearch == null) {
            return null;
        }
        String wanted = search == null ? "" : search;
        // A blank search is the facet-only browse, and only matches a search that had no text either.
        if (wanted.isBlank() != lastMatchedSearch.isBlank()) {
            return null;
        }
        Set<String> searched = new HashSet<>();
        TermBalancedSearch.terms(lastMatchedSearch).forEach(term -> searched.add(term.toLowerCase(Locale.ROOT)));
        boolean covered = TermBalancedSearch.terms(wanted).stream().allMatch(term -> searched.contains(term.toLowerCase(Locale.ROOT)));
        return covered ? lastMatchedFacets : null;
    }

    /** @return the latest search that found something, minus its terms that matched nothing; null when there is none */
    String lastMatchedSearchWithoutMisses() {
        List<String> kept = splitUnmatched(lastMatchedSearch).kept();
        return kept.isEmpty() ? null : String.join(" | ", kept);
    }

    /** @return whether the latest search that found something was narrowed by facets */
    boolean lastSearchWasNarrowed() {
        return lastMatchedSearch != null && !lastMatchedFacets.isEmpty();
    }

    /** @return how many search_concepts calls this turn found something */
    int matchedSearches() {
        return matchedSearches;
    }
}
