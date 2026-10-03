package edu.harvard.hms.dbmi.avillach.mcp.tool;

import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryConcept;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryPage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the dictionary pages of a several-term {@code search_concepts} call into one result. Concepts are taken in turn from each term's
 * page, one position at a time, in the order the terms were given, so the dictionary's order holds within a term and the terms interleave.
 * A concept path that several terms returned appears once, at its first position, and lists every term that returned it, in the order the
 * terms were given.
 */
final class TermPages {

    private TermPages() {}

    /**
     * One term's outcome: the dictionary page it returned, or the failure its call raised.
     *
     * @param term the search term
     * @param page the page, or null when the call failed
     * @param failure the failure, or null when the call succeeded
     */
    record TermPage(String term, DictionaryPage page, ToolFailure failure) {

        /**
         * A term whose search returned a page.
         *
         * @param term the search term
         * @param page the page
         * @return the outcome
         */
        static TermPage found(String term, DictionaryPage page) {
            return new TermPage(term, page, null);
        }

        /**
         * A term whose search failed.
         *
         * @param term the search term
         * @param failure the model-facing failure
         * @return the outcome
         */
        static TermPage failed(String term, ToolFailure failure) {
            return new TermPage(term, null, failure);
        }
    }

    /**
     * Merges the outcomes into one result.
     *
     * @param outcomes one outcome per term, in the order the terms were given
     * @param page the zero-based page asked of each term
     * @param pageSize the page size asked of each term
     * @param cap the most concepts the result carries
     * @return the merged result, with {@code truncated} set when more than {@code cap} distinct concepts came back, and one warning per
     *         failed term
     * @throws ToolFailure the first term's failure when every term failed
     */
    static ConceptSearchResult merge(List<TermPage> outcomes, int page, int pageSize, int cap) {
        List<TermPage> found = outcomes.stream().filter(o -> o.failure() == null).toList();
        if (found.isEmpty()) {
            throw outcomes.getFirst().failure();
        }
        List<String> warnings = outcomes.stream().filter(o -> o.failure() != null)
            .map(o -> "Term '" + o.term() + "' failed: " + o.failure().getMessage()).toList();
        Map<String, Merged> merged = new LinkedHashMap<>();
        int longest = found.stream().mapToInt(o -> o.page().concepts().size()).max().orElse(0);
        for (int position = 0; position < longest; position++) {
            for (TermPage outcome : found) {
                List<DictionaryConcept> concepts = outcome.page().concepts();
                if (position < concepts.size()) {
                    DictionaryConcept concept = concepts.get(position);
                    merged.computeIfAbsent(concept.conceptPath(), path -> new Merged(concept, new ArrayList<>())).add(outcome.term());
                }
            }
        }
        List<String> terms = outcomes.stream().map(TermPage::term).toList();
        Comparator<String> givenOrder = Comparator.comparingInt(terms::indexOf);
        List<ConceptSummary> concepts = merged.values().stream().limit(cap)
            .map(m -> ConceptSummary.summary(m.concept()).withMatchedTerms(m.terms().stream().sorted(givenOrder).toList())).toList();
        long total = found.stream().mapToLong(o -> o.page().total()).sum();
        return new ConceptSearchResult(
            null, terms, page, pageSize, total, concepts, merged.size() > cap, warnings.isEmpty() ? null : warnings
        );
    }

    private record Merged(DictionaryConcept concept, List<String> terms) {

        void add(String term) {
            if (!terms.contains(term)) {
                terms.add(term);
            }
        }
    }
}
