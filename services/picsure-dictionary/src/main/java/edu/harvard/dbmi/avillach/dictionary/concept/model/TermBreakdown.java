package edu.harvard.dbmi.avillach.dictionary.concept.model;

import java.util.List;
import java.util.Map;

/**
 * How the |-separated terms of a concept search divide its results.
 *
 * @param termTotals how many concepts each term matches on its own, in the order the terms were given
 * @param matches for each concept of the page, in page order, the terms that match it
 */
public record TermBreakdown(Map<String, Long> termTotals, List<ConceptMatch> matches) {
}
