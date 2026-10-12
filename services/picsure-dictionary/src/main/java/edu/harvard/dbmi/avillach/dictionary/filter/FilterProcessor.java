package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Preprocesses filter input before query generation. Sorts facets and consents for stable cache keys, and sanitizes the search string to
 * prevent tsquery syntax errors from special characters.
 */
@Component
public class FilterProcessor {

    private static final int MAX_SEARCH_LENGTH = 200;

    public Filter processsFilter(Filter filter) {
        List<Facet> newFacets = filter.facets();
        List<String> newConsents = filter.consents();
        if (filter.facets() != null) {
            newFacets = new ArrayList<>(filter.facets());
            newFacets.sort(Comparator.comparing(Facet::name));
        }
        if (filter.consents() != null) {
            newConsents = new ArrayList<>(newConsents);
            newConsents.sort(Comparator.comparing(Function.identity()));
        }
        filter = new Filter(newFacets, sanitizeSearch(filter.search()), newConsents);
        return filter;
    }

    /**
     * Sanitizes a raw search string for safe use in PostgreSQL to_tsquery(). A '|' separates alternative terms (OR); within each term,
     * strips all characters that are not Unicode letters, digits, or whitespace — this prevents tsquery operator injection (&amp;, !, :, *,
     * etc.) — then collapses runs of whitespace and trims. Empty terms are dropped and the rest are joined with a bare '|', e.g. "
     * creatinine | white blood-cell |" becomes "creatinine|white blood cell". Returns empty string for input that contains only special
     * characters (e.g. "&amp;"), which downstream code treats as no search.
     */
    /**
     * Splits a sanitized search into its alternative terms.
     *
     * @param sanitizedSearch a search that {@link #sanitizeSearch} produced, possibly null
     * @return the distinct terms in the order given, empty for a null or blank search
     */
    public static List<String> searchTerms(String sanitizedSearch) {
        if (sanitizedSearch == null || sanitizedSearch.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sanitizedSearch.split("\\|")).filter(term -> !term.isEmpty()).distinct().toList();
    }

    static String sanitizeSearch(String search) {
        if (search == null) {
            return null;
        }
        if (search.length() > MAX_SEARCH_LENGTH) {
            search = search.substring(0, MAX_SEARCH_LENGTH);
        }
        return Arrays.stream(search.split("\\|")).map(term -> term.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim())
            .filter(term -> !term.isEmpty()).collect(Collectors.joining("|"));
    }
}
