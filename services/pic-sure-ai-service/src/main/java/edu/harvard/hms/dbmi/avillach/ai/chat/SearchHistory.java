package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

/** What search_concepts calls found: which terms matched something or nothing, and the latest search that found something. */
final class SearchHistory {

    /** Lower case. */
    private final Set<String> unmatchedTerms = new HashSet<>();
    /** Lower case; terms a search_concepts call found something for, here or in an earlier turn. */
    private final Set<String> matchedTerms = new HashSet<>();
    private String lastMatchedSearch;
    private List<FacetSelection> lastMatchedFacets = List.of();
    private int matchedSearches;

    /**
     * @param arguments a successful search_concepts call's arguments
     * @param result its result
     */
    void learn(JsonNode arguments, JsonNode result) {
        learnTermMatches(arguments, result);
        learnLastMatchedSearch(arguments, result);
    }

    /** Also how a later turn carries over an earlier turn's per-term hits and misses, without its matched searches. */
    void learnTermMatches(JsonNode arguments, JsonNode result) {
        JsonNode totals = result.path("termTotals");
        if (totals.isObject()) {
            totals.properties().forEach(e -> record(e.getKey(), e.getValue().asInt(0) > 0));
            return;
        }
        List<String> terms = TermBalancedSearch.terms(TermBalancedSearch.searchText(arguments));
        if (terms.size() == 1 && result.path("total").isInt()) {
            record(terms.getFirst(), result.path("total").asInt() > 0);
        }
    }

    private void record(String term, boolean matched) {
        String key = key(term);
        if (matched) {
            unmatchedTerms.remove(key);
            if (!key.isEmpty()) {
                matchedTerms.add(key);
            }
        } else if (!key.isEmpty()) {
            unmatchedTerms.add(key);
        }
    }

    private void learnLastMatchedSearch(JsonNode arguments, JsonNode result) {
        String text = TermBalancedSearch.searchText(arguments);
        JsonNode concepts = result.path("concepts");
        List<FacetSelection> narrowing = FacetSelection.listedIn(arguments);
        boolean hasText = text != null && !text.isBlank();
        // A facet-only browse has no search text but still counts as a matched search.
        boolean browse = !hasText && !narrowing.isEmpty();
        if ((hasText || browse) && concepts.isArray() && !concepts.isEmpty()) {
            matchedSearches++;
            if (hasText && !result.path("termTotals").isObject()) {
                // A multi-term search whose result has no per-term totals still found its terms together.
                TermBalancedSearch.terms(text).stream().map(SearchHistory::key).filter(k -> !unmatchedTerms.contains(k))
                    .forEach(matchedTerms::add);
            }
            lastMatchedSearch = hasText ? text : "";
            lastMatchedFacets = narrowing;
        }
    }

    /**
     * What {@link #splitUnmatched} and {@link #splitUnverified} each mean by the two lists is in their own docs.
     *
     * @param kept terms that may stay in the search
     * @param dropped terms that may not
     */
    record TermSplit(List<String> kept, List<String> dropped) {
    }

    /**
     * @param search {@code |}-joined search text, or null
     * @return its terms, split by whether they matched nothing: {@code kept} holds those that matched something or were never searched,
     *         {@code dropped} those that matched nothing
     */
    TermSplit splitUnmatched(String search) {
        Map<Boolean, List<String>> byMiss =
            TermBalancedSearch.terms(search).stream().collect(Collectors.partitioningBy(term -> unmatchedTerms.contains(key(term))));
        return new TermSplit(byMiss.get(false), byMiss.get(true));
    }

    /**
     * The terms a proposal may put in the researcher's search box: the ones a search_concepts call found something for, and the ones
     * already in the box. The model's search text goes to the researcher's browser, so nothing it has not looked up is passed on.
     *
     * @param search {@code |}-joined proposed search text, or null
     * @param onScreen the search already on the researcher's screen, or null
     * @return its terms, split by whether they may be shown; {@code dropped} holds those that matched nothing or were never searched
     */
    TermSplit splitUnverified(String search, String onScreen) {
        Set<String> shown = TermBalancedSearch.terms(onScreen).stream().map(SearchHistory::key).collect(Collectors.toSet());
        Map<Boolean, List<String>> byVerified = TermBalancedSearch.terms(search).stream().collect(Collectors.partitioningBy(term -> {
            String key = key(term);
            return !unmatchedTerms.contains(key) && (matchedTerms.contains(key) || shown.contains(key));
        }));
        return new TermSplit(byVerified.get(true), byVerified.get(false));
    }

    private static String key(String term) {
        return term.trim().toLowerCase(Locale.ROOT);
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
        TermBalancedSearch.terms(lastMatchedSearch).forEach(term -> searched.add(key(term)));
        boolean covered = TermBalancedSearch.terms(wanted).stream().allMatch(term -> searched.contains(key(term)));
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
